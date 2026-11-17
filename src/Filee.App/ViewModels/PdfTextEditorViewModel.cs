using System.Collections.ObjectModel;
using Avalonia.Media.Imaging;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Filee.Engines.Pdf;
using Filee.Core.Localization;
using Microsoft.Extensions.Logging;

namespace Filee.App.ViewModels;

public sealed partial class PdfTextEntry(PdfTextObject source) : ObservableObject
{
    public int PageIndex => source.PageIndex;
    public int ObjectIndex => source.ObjectIndex;
    public string Text => source.Text;
    public string BoundsText => $"{source.Left:0.#}, {source.Bottom:0.#} — {source.Right:0.#}, {source.Top:0.#}";
}

/// <summary>Edits existing selectable PDF text objects on a private working copy; the original is never overwritten.</summary>
public sealed partial class PdfTextEditorViewModel : ObservableObject, IDisposable
{
    private readonly string _sourcePath;
    private readonly string _workingFolder;
    private readonly ILocalizer _loc;
    private readonly ILogger? _log;
    private PdfPreviewDocument? _preview;
    private bool _disposed;

    public PdfTextEditorViewModel(string sourcePath, ILocalizer loc, ILogger? log = null)
    {
        _sourcePath = Path.GetFullPath(sourcePath);
        _loc = loc;
        _log = log;
        _workingFolder = Path.Combine(Path.GetTempPath(), "Hoot", "PdfTextEdit", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_workingFolder);
        WorkingPath = Path.Combine(_workingFolder, Path.GetFileName(_sourcePath));
        File.Copy(_sourcePath, WorkingPath);
        SuggestedOutputPath = Path.Combine(Path.GetDirectoryName(_sourcePath)!,
            Path.GetFileNameWithoutExtension(_sourcePath) + "_edited.pdf");
    }

    public string WorkingPath { get; }
    public string SuggestedOutputPath { get; }
    public ObservableCollection<PdfTextEntry> Texts { get; } = [];

    [ObservableProperty] private int _pageNumber = 1;
    [ObservableProperty] private int _pageCount;
    [ObservableProperty] private Bitmap? _previewImage;
    [ObservableProperty] private PdfTextEntry? _selectedText;
    [ObservableProperty] private string _replacementText = "";
    [ObservableProperty] private string _status = "";
    [ObservableProperty] private bool _isBusy;

    public bool HasTexts => Texts.Count > 0;
    public bool HasPreview => PreviewImage is not null;
    public bool CanReplace => !IsBusy && SelectedText is not null && !string.IsNullOrWhiteSpace(ReplacementText);
    public string PageCounterText => _loc.Format("pdf_text_editor.page_counter", PageNumber, PageCount);

    partial void OnSelectedTextChanged(PdfTextEntry? value)
    {
        ReplacementText = value?.Text ?? "";
        ReplaceTextCommand.NotifyCanExecuteChanged();
    }

    partial void OnReplacementTextChanged(string value) => ReplaceTextCommand.NotifyCanExecuteChanged();
    partial void OnIsBusyChanged(bool value) => ReplaceTextCommand.NotifyCanExecuteChanged();
    partial void OnPageNumberChanged(int value) => OnPropertyChanged(nameof(PageCounterText));
    partial void OnPageCountChanged(int value) => OnPropertyChanged(nameof(PageCounterText));

    [RelayCommand]
    public async Task LoadAsync(CancellationToken cancellationToken = default)
    {
        IsBusy = true;
        try
        {
            var preview = await Task.Run(() =>
            {
                cancellationToken.ThrowIfCancellationRequested();
                return PdfPreviewDocument.Open(WorkingPath);
            }, cancellationToken);
            _preview = preview;
            PageCount = preview.PageCount;
            PageNumber = Math.Clamp(PageNumber, 1, Math.Max(1, PageCount));
            await ReloadPageAsync(cancellationToken);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException and not OperationCanceledException)
        {
            _log?.LogWarning(ex, "Could not load PDF text for editing");
            Status = ex.Message;
        }
        finally { IsBusy = false; }
    }

    [RelayCommand]
    private async Task PreviousPageAsync() { if (PageNumber > 1) { PageNumber--; await ReloadPageAsync(); } }

    [RelayCommand]
    private async Task NextPageAsync() { if (PageNumber < PageCount) { PageNumber++; await ReloadPageAsync(); } }

    [RelayCommand(CanExecute = nameof(CanReplace))]
    private async Task ReplaceTextAsync()
    {
        if (SelectedText is not { } selected || string.IsNullOrWhiteSpace(ReplacementText))
            return;
        IsBusy = true;
        try
        {
            var entry = selected;
            var replacement = ReplacementText;
            await Task.Run(() => PdfTextObjectEditor.ReplaceTextObject(WorkingPath, WorkingPath,
                entry.PageIndex, entry.ObjectIndex, replacement));
            // PdfPreviewDocument intentionally snapshots file bytes. Re-open it after editing so the preview cannot
            // silently keep rendering the original page after the text object has been replaced.
            _preview = await Task.Run(() => PdfPreviewDocument.Open(WorkingPath));
            await ReloadPageAsync();
            Status = _loc["pdf_text_editor.status_replaced"];
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            _log?.LogWarning(ex, "Could not replace selected PDF text");
            Status = ex.Message;
        }
        finally { IsBusy = false; }
    }

    public async Task ReloadPageAsync(CancellationToken cancellationToken = default)
    {
        var preview = _preview;
        if (preview is null || PageNumber < 1 || PageNumber > PageCount)
            return;
        IsBusy = true;
        try
        {
            var pageIndex = PageNumber - 1;
            var data = await Task.Run(() =>
            {
                cancellationToken.ThrowIfCancellationRequested();
                var image = preview.RenderPage(PageNumber, 1500, cancellationToken).Png;
                var texts = PdfTextObjectEditor.ReadTextObjects(WorkingPath, pageIndex);
                return (image, texts);
            }, cancellationToken);
            using var stream = new MemoryStream(data.image);
            var next = new Bitmap(stream);
            var previous = PreviewImage;
            PreviewImage = next;
            previous?.Dispose();
            Texts.Clear();
            foreach (var text in data.texts)
                Texts.Add(new PdfTextEntry(text));
            SelectedText = Texts.FirstOrDefault();
            Status = HasTexts ? _loc["pdf_text_editor.status_select"] : _loc["pdf_text_editor.no_text"];
            OnPropertyChanged(nameof(HasTexts));
            OnPropertyChanged(nameof(HasPreview));
        }
        catch (Exception ex) when (ex is not OutOfMemoryException and not OperationCanceledException)
        {
            _log?.LogWarning(ex, "Could not refresh PDF text editor page {PageNumber}", PageNumber);
            Status = ex.Message;
        }
        finally { IsBusy = false; }
    }

    public async Task ExportAsync(string outputPath)
    {
        var fullPath = Path.GetFullPath(outputPath);
        var pathComparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        if (string.Equals(fullPath, _sourcePath, pathComparison))
            throw new InvalidOperationException("Choose a new filename so the original PDF remains untouched.");
        IsBusy = true;
        try
        {
            await Task.Run(() => File.Copy(WorkingPath, fullPath, overwrite: true));
            Status = _loc.Format("pdf_text_editor.status_saved", Path.GetFileName(fullPath));
        }
        finally { IsBusy = false; }
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        PreviewImage?.Dispose();
        PreviewImage = null;
        try { if (Directory.Exists(_workingFolder)) Directory.Delete(_workingFolder, recursive: true); }
        catch (IOException ex) { _log?.LogDebug(ex, "Could not remove the temporary PDF editing copy"); }
        catch (UnauthorizedAccessException ex) { _log?.LogDebug(ex, "Could not remove the temporary PDF editing copy"); }
    }
}

using System.Collections.ObjectModel;
using Avalonia.Media.Imaging;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Filee.Core.Localization;
using Filee.Engines.Pdf;
using Microsoft.Extensions.Logging;

namespace Filee.App.ViewModels;

public sealed partial class PdfTextEntry(PdfTextObject source) : ObservableObject
{
    public int PageIndex => source.PageIndex;
    public int ObjectIndex => source.ObjectIndex;
    public string Text => source.Text;
    public double Left => source.Left;
    public double Bottom => source.Bottom;
    public double Right => source.Right;
    public double Top => source.Top;
    public double FontSize => source.FontSize;
    public string BoundsText => $"{source.Left:0.#}, {source.Bottom:0.#} — {source.Right:0.#}, {source.Top:0.#}";
}

/// <summary>Edits existing selectable PDF text objects on a private working copy; the original is never overwritten.</summary>
public sealed partial class PdfTextEditorViewModel : ObservableObject, IDisposable
{
    private readonly string _sourcePath;
    private readonly string _workingFolder;
    private readonly ILocalizer _loc;
    private readonly ILogger? _log;
    private readonly Stack<string> _undoStack = new();
    private PdfPreviewDocument? _preview;
    private bool _disposed;
    private bool _suppressSelectionSync;

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
    public ObservableCollection<PdfTextEntry> SelectedTexts { get; } = [];

    [ObservableProperty] private int _pageNumber = 1;
    [ObservableProperty] private int _pageCount;
    [ObservableProperty] private double _pageWidth = 612;
    [ObservableProperty] private double _pageHeight = 792;
    [ObservableProperty] private Bitmap? _previewImage;
    [ObservableProperty] private PdfTextEntry? _selectedText;
    [ObservableProperty] private string _replacementText = "";
    [ObservableProperty] private string _newText = "";
    [ObservableProperty] private double _newTextFontSize = 14;
    [ObservableProperty] private double _insertX = 72;
    [ObservableProperty] private double _insertY = 700;
    [ObservableProperty] private string _status = "";
    [ObservableProperty] private bool _isBusy;

    public bool HasTexts => Texts.Count > 0;
    public bool HasPreview => PreviewImage is not null;
    public bool HasSelection => SelectedTexts.Count > 0;
    public bool CanReplace => !IsBusy && HasSelection && !string.IsNullOrWhiteSpace(ReplacementText);
    public bool CanDelete => !IsBusy && HasSelection;
    public bool CanAddText => !IsBusy && PageCount > 0 && !string.IsNullOrWhiteSpace(NewText);
    public bool CanUndo => !IsBusy && _undoStack.Count > 0;
    public bool CanGoPrevious => !IsBusy && PageNumber > 1;
    public bool CanGoNext => !IsBusy && PageNumber < PageCount;
    public string PageCounterText => _loc.Format("pdf_text_editor.page_counter", PageNumber, Math.Max(1, PageCount));
    public string InsertPositionText => $"Click page to place at ({InsertX:0.#}, {InsertY:0.#})";
    public string SelectedSummaryText => SelectedTexts.Count switch
    {
        0 => "Click or drag across text on the page to select it",
        1 => $"Selected: \"{SelectedTexts[0].Text}\"",
        _ => $"Selected {SelectedTexts.Count} text items",
    };

    partial void OnSelectedTextChanged(PdfTextEntry? value)
    {
        if (!_suppressSelectionSync && value is not null && (SelectedTexts.Count != 1 || !ReferenceEquals(SelectedTexts[0], value)))
        {
            SelectedTexts.Clear();
            SelectedTexts.Add(value);
            ReplacementText = value.Text;
        }
        NotifyEditCommands();
    }

    partial void OnReplacementTextChanged(string value) => NotifyEditCommands();
    partial void OnNewTextChanged(string value) => NotifyEditCommands();
    partial void OnIsBusyChanged(bool value) => NotifyEditCommands();
    partial void OnPageNumberChanged(int value) => NotifyEditCommands();
    partial void OnPageCountChanged(int value) => NotifyEditCommands();
    partial void OnInsertXChanged(double value) => OnPropertyChanged(nameof(InsertPositionText));
    partial void OnInsertYChanged(double value) => OnPropertyChanged(nameof(InsertPositionText));

    private void NotifyEditCommands()
    {
        OnPropertyChanged(nameof(PageCounterText));
        OnPropertyChanged(nameof(HasSelection));
        OnPropertyChanged(nameof(SelectedSummaryText));
        OnPropertyChanged(nameof(CanReplace));
        OnPropertyChanged(nameof(CanDelete));
        OnPropertyChanged(nameof(CanAddText));
        OnPropertyChanged(nameof(CanUndo));
        OnPropertyChanged(nameof(CanGoPrevious));
        OnPropertyChanged(nameof(CanGoNext));
        ReplaceTextCommand.NotifyCanExecuteChanged();
        DeleteTextCommand.NotifyCanExecuteChanged();
        AddTextCommand.NotifyCanExecuteChanged();
        UndoCommand.NotifyCanExecuteChanged();
        PreviousPageCommand.NotifyCanExecuteChanged();
        NextPageCommand.NotifyCanExecuteChanged();
    }

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
        finally { IsBusy = false; NotifyEditCommands(); }
    }

    [RelayCommand(CanExecute = nameof(CanGoPrevious))]
    private async Task PreviousPageAsync() { if (PageNumber > 1) { PageNumber--; await ReloadPageAsync(); } }

    [RelayCommand(CanExecute = nameof(CanGoNext))]
    private async Task NextPageAsync() { if (PageNumber < PageCount) { PageNumber++; await ReloadPageAsync(); } }

    /// <summary>Selects a text object at the given PDF point coordinates (bottom-left origin), or updates the insertion point.</summary>
    public void HandlePreviewClick(double pdfX, double pdfY)
    {
        InsertX = Math.Clamp(pdfX, 0, PageWidth);
        InsertY = Math.Clamp(pdfY, 0, PageHeight);

        const double tolerance = 4.0;
        var hit = Texts.FirstOrDefault(t =>
            pdfX >= t.Left - tolerance && pdfX <= t.Right + tolerance &&
            pdfY >= t.Bottom - tolerance && pdfY <= t.Top + tolerance);
        SelectedTexts.Clear();
        _suppressSelectionSync = true;
        try
        {
            if (hit is not null)
            {
                SelectedTexts.Add(hit);
                SelectedText = hit;
                ReplacementText = hit.Text;
                Status = $"Selected \"{hit.Text}\" — type replacement and click Replace.";
            }
            else
            {
                SelectedText = null;
                ReplacementText = "";
                Status = $"Insertion point set to ({InsertX:0.#}, {InsertY:0.#}).";
            }
        }
        finally { _suppressSelectionSync = false; }
        NotifyEditCommands();
    }

    /// <summary>Selects all text objects intersecting the dragged PDF coordinate box in reading order.</summary>
    public void HandlePreviewDragSelect(double x1, double y1, double x2, double y2)
    {
        var minX = Math.Min(x1, x2);
        var maxX = Math.Max(x1, x2);
        var minY = Math.Min(y1, y2);
        var maxY = Math.Max(y1, y2);

        var hits = Texts
            .Where(t => t.Right >= minX && t.Left <= maxX && t.Top >= minY && t.Bottom <= maxY)
            .OrderByDescending(t => Math.Round(t.Bottom / 4.0))
            .ThenBy(t => t.Left)
            .ToList();

        SelectedTexts.Clear();
        foreach (var hit in hits)
            SelectedTexts.Add(hit);

        _suppressSelectionSync = true;
        try { SelectedText = hits.FirstOrDefault(); }
        finally { _suppressSelectionSync = false; }
        ReplacementText = string.Join(" ", hits.Select(h => h.Text));
        Status = hits.Count > 0
            ? $"Selected {hits.Count} text item(s) — type replacement and click Replace."
            : "No selectable text in that area.";
        NotifyEditCommands();
    }

    private void PushUndoSnapshot()
    {
        var snapshot = Path.Combine(_workingFolder, $"undo_{_undoStack.Count}_{Guid.NewGuid():N}.pdf");
        File.Copy(WorkingPath, snapshot, overwrite: true);
        _undoStack.Push(snapshot);
    }

    [RelayCommand(CanExecute = nameof(CanReplace))]
    private async Task ReplaceTextAsync()
    {
        if (SelectedTexts.Count == 0 || string.IsNullOrWhiteSpace(ReplacementText))
            return;
        IsBusy = true;
        try
        {
            var pageIndex = PageNumber - 1;
            var objectIndices = SelectedTexts.Select(t => t.ObjectIndex).ToList();
            var replacement = ReplacementText;
            PushUndoSnapshot();
            await Task.Run(() => PdfTextObjectEditor.ReplaceTextObjects(WorkingPath, WorkingPath,
                pageIndex, objectIndices, replacement));
            _preview = await Task.Run(() => PdfPreviewDocument.Open(WorkingPath));
            await ReloadPageAsync();
            Status = _loc["pdf_text_editor.status_replaced"];
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            _log?.LogWarning(ex, "Could not replace selected PDF text");
            Status = ex.Message;
        }
        finally { IsBusy = false; NotifyEditCommands(); }
    }

    [RelayCommand(CanExecute = nameof(CanDelete))]
    private async Task DeleteTextAsync()
    {
        if (SelectedTexts.Count == 0)
            return;
        IsBusy = true;
        try
        {
            var pageIndex = PageNumber - 1;
            var objectIndices = SelectedTexts.Select(t => t.ObjectIndex).ToList();
            PushUndoSnapshot();
            await Task.Run(() => PdfTextObjectEditor.DeleteTextObjects(WorkingPath, WorkingPath,
                pageIndex, objectIndices));
            _preview = await Task.Run(() => PdfPreviewDocument.Open(WorkingPath));
            await ReloadPageAsync();
            Status = "Selected text removed.";
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            _log?.LogWarning(ex, "Could not delete selected PDF text");
            Status = ex.Message;
        }
        finally { IsBusy = false; NotifyEditCommands(); }
    }

    [RelayCommand(CanExecute = nameof(CanAddText))]
    private async Task AddTextAsync()
    {
        if (PageCount <= 0 || string.IsNullOrWhiteSpace(NewText))
            return;
        IsBusy = true;
        try
        {
            var pageIndex = PageNumber - 1;
            var text = NewText;
            var x = InsertX;
            var y = InsertY;
            var fontSize = NewTextFontSize > 0 ? NewTextFontSize : 14;
            PushUndoSnapshot();
            await Task.Run(() => PdfTextObjectEditor.AddTextObject(WorkingPath, WorkingPath,
                pageIndex, text, x, y, fontSize));
            _preview = await Task.Run(() => PdfPreviewDocument.Open(WorkingPath));
            NewText = "";
            await ReloadPageAsync();
            Status = "Added new vector text.";
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            _log?.LogWarning(ex, "Could not add PDF text");
            Status = ex.Message;
        }
        finally { IsBusy = false; NotifyEditCommands(); }
    }

    [RelayCommand(CanExecute = nameof(CanUndo))]
    private async Task UndoAsync()
    {
        if (_undoStack.Count == 0)
            return;
        IsBusy = true;
        try
        {
            var snapshot = _undoStack.Pop();
            await Task.Run(() => File.Copy(snapshot, WorkingPath, overwrite: true));
            try { File.Delete(snapshot); } catch (IOException) { }
            _preview = await Task.Run(() => PdfPreviewDocument.Open(WorkingPath));
            await ReloadPageAsync();
            Status = "Reverted last PDF text edit.";
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            _log?.LogWarning(ex, "Could not undo PDF text edit");
            Status = ex.Message;
        }
        finally { IsBusy = false; NotifyEditCommands(); }
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
                var info = PdfTextObjectEditor.ReadPageInfo(WorkingPath, pageIndex);
                return (image, info);
            }, cancellationToken);
            PageWidth = data.info.Width > 0 ? data.info.Width : 612;
            PageHeight = data.info.Height > 0 ? data.info.Height : 792;
            using var stream = new MemoryStream(data.image);
            var next = new Bitmap(stream);
            var previous = PreviewImage;
            PreviewImage = next;
            previous?.Dispose();
            Texts.Clear();
            foreach (var text in data.info.Objects)
                Texts.Add(new PdfTextEntry(text));
            SelectedTexts.Clear();
            _suppressSelectionSync = true;
            try { SelectedText = null; }
            finally { _suppressSelectionSync = false; }
            ReplacementText = "";
            Status = HasTexts ? "Click or drag on any text in the PDF to edit it." : _loc["pdf_text_editor.no_text"];
            OnPropertyChanged(nameof(HasTexts));
            OnPropertyChanged(nameof(HasPreview));
        }
        catch (Exception ex) when (ex is not OutOfMemoryException and not OperationCanceledException)
        {
            _log?.LogWarning(ex, "Could not refresh PDF text editor page {PageNumber}", PageNumber);
            Status = ex.Message;
        }
        finally { IsBusy = false; NotifyEditCommands(); }
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

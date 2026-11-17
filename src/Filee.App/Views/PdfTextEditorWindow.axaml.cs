using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Filee.App.Services;
using Filee.App.ViewModels;

namespace Filee.App.Views;

public partial class PdfTextEditorWindow : Window
{
    private static readonly FilePickerFileType PdfFile = new("PDF")
    {
        Patterns = ["*.pdf"],
        MimeTypes = ["application/pdf"],
        AppleUniformTypeIdentifiers = ["com.adobe.pdf"],
    };

    public PdfTextEditorWindow()
    {
        InitializeComponent();
        Motion.Track(this);
    }

    private async void OnOpened(object? sender, EventArgs e)
    {
        if (DataContext is PdfTextEditorViewModel vm)
            await vm.LoadAsync();
    }

    private void OnClosing(object? sender, WindowClosingEventArgs e)
    {
        if (DataContext is PdfTextEditorViewModel { IsBusy: true })
        {
            e.Cancel = true;
            if (DataContext is PdfTextEditorViewModel vm)
                vm.Status = this.TryFindResource("pdf_text_editor.status_wait", out var status)
                    ? status?.ToString() ?? "Please wait for the current PDF operation to finish."
                    : "Please wait for the current PDF operation to finish.";
        }
    }

    private void OnClosed(object? sender, EventArgs e)
    {
        if (DataContext is PdfTextEditorViewModel vm)
            vm.Dispose();
    }

    private void OnCancel(object? sender, RoutedEventArgs e) => Close();

    private async void OnSaveAs(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not PdfTextEditorViewModel vm || vm.IsBusy)
            return;

        var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = this.TryFindResource("pdf_text_editor.save_dialog", out var title)
                ? title?.ToString() ?? "Save edited PDF"
                : "Save edited PDF",
            SuggestedFileName = Path.GetFileName(vm.SuggestedOutputPath),
            DefaultExtension = "pdf",
            FileTypeChoices = [PdfFile],
            ShowOverwritePrompt = true,
        });
        var path = file?.TryGetLocalPath();
        if (path is null)
            return;

        try
        {
            await vm.ExportAsync(path);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            vm.Status = ex.Message;
        }
    }
}

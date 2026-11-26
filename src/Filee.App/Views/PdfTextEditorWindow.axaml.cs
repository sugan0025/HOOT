using System.Collections.Specialized;
using System.ComponentModel;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
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

    private PdfTextEditorViewModel? _subscribedVm;
    private Point? _dragStart;
    private bool _isDragging;

    public PdfTextEditorWindow()
    {
        InitializeComponent();
        Motion.Track(this);
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object? sender, EventArgs e)
    {
        if (_subscribedVm is not null)
        {
            _subscribedVm.PropertyChanged -= OnViewModelPropertyChanged;
            _subscribedVm.SelectedTexts.CollectionChanged -= OnSelectedTextsChanged;
        }
        _subscribedVm = DataContext as PdfTextEditorViewModel;
        if (_subscribedVm is not null)
        {
            _subscribedVm.PropertyChanged += OnViewModelPropertyChanged;
            _subscribedVm.SelectedTexts.CollectionChanged += OnSelectedTextsChanged;
        }
        UpdateSelectionHighlights();
    }

    private void OnSelectedTextsChanged(object? sender, NotifyCollectionChangedEventArgs e) =>
        UpdateSelectionHighlights();

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PdfTextEditorViewModel.SelectedText) or nameof(PdfTextEditorViewModel.PreviewImage)
            or nameof(PdfTextEditorViewModel.PageWidth) or nameof(PdfTextEditorViewModel.PageHeight))
            UpdateSelectionHighlights();
    }

    private void OnPreviewPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is not PdfTextEditorViewModel vm || vm.IsBusy)
            return;
        _dragStart = e.GetPosition(PreviewImageControl);
        _isDragging = false;
        e.Pointer.Capture(PreviewImageControl);
    }

    private void OnPreviewPointerMoved(object? sender, PointerEventArgs e)
    {
        if (_dragStart is not { } start || DataContext is not PdfTextEditorViewModel vm || vm.IsBusy)
            return;
        var current = e.GetPosition(PreviewImageControl);
        if (!_isDragging && (Math.Abs(current.X - start.X) > 4 || Math.Abs(current.Y - start.Y) > 4))
            _isDragging = true;
        if (_isDragging && DragMarquee is not null)
        {
            var left = Math.Max(0, Math.Min(start.X, current.X));
            var top = Math.Max(0, Math.Min(start.Y, current.Y));
            var right = Math.Min(vm.PageWidth, Math.Max(start.X, current.X));
            var bottom = Math.Min(vm.PageHeight, Math.Max(start.Y, current.Y));
            Canvas.SetLeft(DragMarquee, left);
            Canvas.SetTop(DragMarquee, top);
            DragMarquee.Width = Math.Max(1, right - left);
            DragMarquee.Height = Math.Max(1, bottom - top);
            DragMarquee.IsVisible = true;
        }
    }

    private void OnPreviewPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (_dragStart is not { } start || DataContext is not PdfTextEditorViewModel vm)
            return;
        var end = e.GetPosition(PreviewImageControl);
        _dragStart = null;
        if (DragMarquee is not null)
            DragMarquee.IsVisible = false;
        if (e.Pointer.Captured == PreviewImageControl)
            e.Pointer.Capture(null);

        if (_isDragging)
        {
            _isDragging = false;
            vm.HandlePreviewDragSelect(start.X, vm.PageHeight - start.Y, end.X, vm.PageHeight - end.Y);
        }
        else
        {
            vm.HandlePreviewClick(end.X, vm.PageHeight - end.Y);
        }
        UpdateSelectionHighlights();
    }

    private void UpdateSelectionHighlights()
    {
        if (HighlightOverlay is null || DragMarquee is null)
            return;

        for (var i = HighlightOverlay.Children.Count - 1; i >= 0; i--)
        {
            if (!ReferenceEquals(HighlightOverlay.Children[i], DragMarquee))
                HighlightOverlay.Children.RemoveAt(i);
        }

        if (DataContext is not PdfTextEditorViewModel vm || vm.SelectedTexts.Count == 0 || vm.PageHeight <= 0)
            return;

        var borderBrush = SolidColorBrush.Parse("#2563EB");
        var fillBrush = SolidColorBrush.Parse("#282563EB");
        foreach (var entry in vm.SelectedTexts)
        {
            var box = new Border
            {
                BorderBrush = borderBrush,
                BorderThickness = new Thickness(1.5),
                Background = fillBrush,
                CornerRadius = new CornerRadius(2),
                Width = Math.Max(4, entry.Right - entry.Left + 4),
                Height = Math.Max(4, entry.Top - entry.Bottom + 4),
            };
            Canvas.SetLeft(box, Math.Max(0, entry.Left - 2));
            Canvas.SetTop(box, Math.Max(0, vm.PageHeight - entry.Top - 2));
            HighlightOverlay.Children.Add(box);
        }
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
        if (_subscribedVm is not null)
        {
            _subscribedVm.PropertyChanged -= OnViewModelPropertyChanged;
            _subscribedVm.SelectedTexts.CollectionChanged -= OnSelectedTextsChanged;
            _subscribedVm = null;
        }
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

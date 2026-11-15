using Avalonia.Controls;
using Avalonia.Interactivity;
using Filee.App.Controls;

namespace Filee.App.Views;

public partial class MainWindow
{
    private void InitializeAndroidReveal()
    {
        PropertyChanged += (_, e) =>
        {
            if ((e.Property == IsVisibleProperty && !IsVisible) ||
                (e.Property == WindowStateProperty && WindowState == WindowState.Minimized))
                AndroidReveal.Dismiss(false);
        };
        Closed += (_, _) => AndroidReveal.Dismiss(false);
    }

    private void OnAndroidMascotClick(object? sender, RoutedEventArgs e) =>
        AndroidReveal.Reveal(SidebarBlob, AndroidMascotButton);
}

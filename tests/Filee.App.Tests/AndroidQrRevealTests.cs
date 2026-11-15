using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.Input;
using Filee.App.Controls;
using Filee.App.Services;

namespace Filee.App.Tests;

[Collection("Motion")]
public sealed class AndroidQrRevealTests
{
    [Fact]
    public void Timeline_is_bounded_and_settles_after_delayed_frames()
    {
        foreach (var step in new[] { 1d / 30, 1d / 60, .3 })
        {
            for (double time = 0; time < 2; time += step)
            {
                var frame = AndroidQrTimeline.At(time, false);
                Assert.InRange(frame.Scale, .025, 1);
                Assert.InRange(frame.Mouth, 0, 1);
                Assert.InRange(frame.BodyScale, .9, 1.00001);
            }
        }
        var end = AndroidQrTimeline.At(30, false);
        Assert.Equal(1, end.Scale);
        Assert.Equal(0, end.Mouth);
        Assert.Equal(1, end.CopyOpacity);
        Assert.True(end.Complete);
        Assert.Equal(0, AndroidQrTimeline.At(.3, false).CardOpacity);
        Assert.Equal(0, AndroidQrTimeline.At(.8, false).CopyOpacity);
    }

    [Fact]
    public void Reduced_motion_only_fades_without_deforming_the_blob()
    {
        var frame = AndroidQrTimeline.At(.075, true);
        Assert.Equal(1, frame.Scale);
        Assert.Equal(1, frame.BodyScale);
        Assert.Equal(0, frame.Mouth);
        Assert.Equal(.5, frame.CardOpacity, 5);
        Assert.True(AndroidQrTimeline.At(.2, true).Complete);
    }

    [AvaloniaFact]
    public void Reveal_loads_artwork_ignores_duplicate_activation_and_cancels_on_escape()
    {
        var blob = new BlobatarControl { Width = 44, Height = 44 };
        var button = new Button { Content = blob };
        var overlay = new AndroidQrReveal();
        var panel = new Panel();
        panel.Children.Add(button);
        panel.Children.Add(overlay);
        var host = new Window { Width = 820, Height = 560, Content = panel };
        try
        {
            host.Show();
            overlay.Reveal(blob, button);
            Assert.True(overlay.IsVisible);
            Assert.True(overlay.IsRevealing);
            Assert.False(blob.IsAnimated);
            // A duplicate must not capture the temporary false animation flag as its original state.
            overlay.Reveal(blob, button);
            overlay.RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Escape });
            Assert.False(overlay.IsVisible);
            Assert.False(overlay.IsRevealing);
            Assert.True(blob.IsAnimated);
            Assert.Equal(0, blob.MouthOpen);
            Assert.Null(blob.RenderTransform);
        }
        finally { host.Close(); }
    }

    [AvaloniaFact]
    public void Detaching_during_reveal_stops_timer_and_restores_pose()
    {
        var blob = new BlobatarControl { Width = 44, Height = 44 };
        var overlay = new AndroidQrReveal();
        var panel = new Panel();
        panel.Children.Add(blob);
        panel.Children.Add(overlay);
        var host = new Window { Width = 820, Height = 560, Content = panel };
        try
        {
            host.Show();
            overlay.Reveal(blob, blob);
            panel.Children.Remove(overlay);
            Assert.False(overlay.IsRevealing);
            Assert.False(overlay.IsVisible);
            Assert.True(blob.IsAnimated);
            Assert.Equal(0, blob.MouthOpen);
        }
        finally { host.Close(); }
    }

    [AvaloniaFact]
    public async Task Settled_card_fits_small_window_and_stops_its_clock()
    {
        var blob = new BlobatarControl
        {
            Width = 44,
            Height = 44,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Bottom
        };
        var overlay = new AndroidQrReveal();
        var panel = new Panel();
        panel.Children.Add(blob);
        panel.Children.Add(overlay);
        var host = new Window { Width = 820, Height = 560, Content = panel };
        try
        {
            host.Show();
            overlay.Reveal(blob, blob);
            await Task.Delay(1400);
            Avalonia.Threading.Dispatcher.UIThread.RunJobs();
            Assert.False(overlay.IsRevealing);
            Assert.True(overlay.IsVisible);
            var card = Assert.IsType<Border>(Assert.Single(overlay.Children));
            Assert.InRange(Canvas.GetLeft(card), 0, overlay.Bounds.Width - card.Bounds.Width);
            Assert.InRange(Canvas.GetTop(card), 0, overlay.Bounds.Height - card.Bounds.Height);
            Assert.Equal(1, card.Opacity);
        }
        finally { host.Close(); }
    }
}

[Collection("MainWindow")]
public sealed class MainWindowAndroidRevealTests
{
    [AvaloniaFact]
    public void Sidebar_button_opens_reveal_and_hiding_main_window_cancels_it()
    {
        TestServices.EnsureInitialized("en");
        var host = new Filee.App.Views.MainWindow();
        try
        {
            host.Show();
            var button = host.FindControl<Button>("AndroidMascotButton")!;
            var overlay = host.FindControl<AndroidQrReveal>("AndroidReveal")!;
            var blob = host.FindControl<BlobatarControl>("SidebarBlob")!;
            button.RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent));
            Assert.True(overlay.IsVisible);
            host.Hide();
            Assert.False(overlay.IsVisible);
            Assert.False(overlay.IsRevealing);
            Assert.True(blob.IsAnimated);
            Assert.Equal(0, blob.MouthOpen);
        }
        finally
        {
            host.Close();
            Avalonia.Threading.Dispatcher.UIThread.RunJobs();
        }
    }
}

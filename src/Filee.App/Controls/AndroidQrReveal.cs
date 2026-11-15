using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using Filee.App.Services;

namespace Filee.App.Controls;

/// <summary>Main-window-only Easter egg. Owns its short reveal clock and sidebar pose.</summary>
public sealed class AndroidQrReveal : Canvas
{
    internal const string DownloadUrl = "https://sugan0025.github.io";
    private readonly Border _card;
    private readonly Image _image;
    private readonly StackPanel _copy;
    private readonly Button _close;
    private readonly DispatcherTimer _timer;
    private readonly Stopwatch _clock = new();
    private BlobatarControl? _blob;
    private Control? _returnFocus;
    private bool _reduced;
    private bool _wasAnimated;
    private bool _wasAudioEnabled;
    private bool _poseOwned;
    private ITransform? _originalTransform;
    private RelativePoint _originalOrigin;
    private Bitmap? _bitmap;

    internal bool IsRevealing => _timer.IsEnabled;

    public AndroidQrReveal()
    {
        IsVisible = false;
        ClipToBounds = true;
        Background = Brushes.Transparent;
        KeyboardNavigation.SetTabNavigation(this, KeyboardNavigationMode.Cycle);
        _image = new Image { Stretch = Stretch.Uniform, Width = 300, Height = 300 };
        _close = new Button
        {
            Content = "×",
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right,
            Padding = new Thickness(8, 2)
        };
        Avalonia.Automation.AutomationProperties.SetName(_close, "Close Android QR code");
        _close.Click += (_, _) => Dismiss();
        var download = new Button { Content = "Open Android download", HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center };
        download.Click += async (_, _) =>
        {
            if (TopLevel.GetTopLevel(this) is { } top)
                await top.Launcher.LaunchUriAsync(new Uri(DownloadUrl));
        };
        _copy = new StackPanel { Spacing = 8 };
        _copy.Children.Add(new TextBlock
        {
            Text = "HOOT is now available for Android",
            FontWeight = FontWeight.SemiBold,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap
        });
        _copy.Children.Add(new TextBlock { Text = "“HOOT HOOT get it”", TextAlignment = TextAlignment.Center });
        _copy.Children.Add(download);
        var content = new StackPanel { Spacing = 8 };
        content.Children.Add(_close);
        content.Children.Add(_image);
        content.Children.Add(_copy);
        _card = new Border
        {
            Background = Brushes.White,
            BorderBrush = new SolidColorBrush(Color.Parse("#8B7DDD")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Padding = new Thickness(16),
            Child = content,
            RenderTransformOrigin = RelativePoint.TopLeft
        };
        _card.SetValue(Avalonia.Controls.Documents.TextElement.ForegroundProperty, new SolidColorBrush(Color.Parse("#29223F")));
        Children.Add(_card);
        PointerPressed += (_, e) =>
        {
            // Only the transparent area outside the card dismisses it.
            if (ReferenceEquals(e.Source, this)) { Dismiss(); e.Handled = true; }
        };
        AddHandler(KeyDownEvent, (_, e) =>
        {
            if (e.Key == Key.Escape) { Dismiss(); e.Handled = true; }
        }, Avalonia.Interactivity.RoutingStrategies.Tunnel);
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.0 / 30) };
        _timer.Tick += (_, _) => RenderFrame();
        SizeChanged += (_, _) => { if (IsVisible) RenderFrame(); };
        DetachedFromVisualTree += (_, _) =>
        {
            Dismiss(false);
            _image.Source = null;
            _bitmap?.Dispose();
            _bitmap = null;
        };
    }

    internal void Reveal(BlobatarControl blob, Control returnFocus)
    {
        if (IsVisible) return;
        if (_bitmap is null)
        {
            using var stream = AssetLoader.Open(new Uri("avares://Hoot/Assets/Android/hoot-android-qr.png"));
            _bitmap = new Bitmap(stream);
            _image.Source = _bitmap;
        }
        _blob = blob;
        _returnFocus = returnFocus;
        _wasAnimated = blob.IsAnimated;
        _wasAudioEnabled = blob.AudioEnabled;
        _originalTransform = blob.RenderTransform;
        _originalOrigin = blob.RenderTransformOrigin;
        _poseOwned = true;
        // Stop ordinary idle/pointer physics from competing with the scripted reveal.
        blob.IsAnimated = false;
        blob.AudioEnabled = false;
        _reduced = !Motion.Enabled;
        _clock.Restart();
        IsVisible = true;
        RenderFrame();
        _close.Focus();
        _timer.Start();
    }

    private void RenderFrame()
    {
        if (_blob is null || !IsVisible) return;
        if (!Motion.Enabled) _reduced = true;
        var seconds = _clock.Elapsed.TotalSeconds;
        var frame = AndroidQrTimeline.At(seconds, _reduced);
        var room = Bounds.Size;
        if (room.Width <= 0 || room.Height <= 0) return;
        var width = Math.Min(334, Math.Max(100, room.Width - 24));
        var imageSize = Math.Min(width - 34, Math.Max(50, room.Height - 220));
        _image.Width = imageSize;
        _image.Height = imageSize;
        _card.Width = width;
        _card.Measure(new Size(width, double.PositiveInfinity));
        var height = _card.DesiredSize.Height;
        var mouth = _blob.TranslatePoint(new Point(_blob.Bounds.Width * .5, _blob.Bounds.Height * .64), this) ?? new Point(40, 40);
        var left = Math.Clamp(mouth.X + 36, 12, Math.Max(12, room.Width - width - 12));
        var top = Math.Clamp(mouth.Y - 24, 12, Math.Max(12, room.Height - height - 12));
        SetLeft(_card, left);
        SetTop(_card, top);
        var scale = frame.Scale;
        // The complete artwork starts at the mouth, then travels to its final readable position.
        _card.RenderTransform = new MatrixTransform(new Matrix(scale, 0, 0, scale,
            (mouth.X - left) * (1 - scale) - width * .5 * scale * (1 - scale),
            (mouth.Y - top) * (1 - scale) - height * .5 * scale * (1 - scale)));
        _card.Opacity = frame.CardOpacity;
        _copy.Opacity = frame.CopyOpacity;
        if (_poseOwned)
        {
            _blob.SetMouthOpen(frame.Mouth);
            _blob.RenderTransformOrigin = RelativePoint.Center;
            _blob.RenderTransform = new ScaleTransform(1 / frame.BodyScale, frame.BodyScale);
        }
        if (frame.Complete)
        {
            _timer.Stop();
            RestoreBlob();
        }
    }

    internal void Dismiss(bool restoreFocus = true)
    {
        _timer.Stop();
        _clock.Stop();
        RestoreBlob();
        IsVisible = false;
        if (restoreFocus) _returnFocus?.Focus();
        _returnFocus = null;
        _blob = null;
    }

    private void RestoreBlob()
    {
        if (_blob is null || !_poseOwned) return;
        _poseOwned = false;
        _blob.SetMouthOpen(0);
        _blob.RenderTransform = _originalTransform;
        _blob.RenderTransformOrigin = _originalOrigin;
        _blob.IsAnimated = _wasAnimated;
        _blob.AudioEnabled = _wasAudioEnabled;
    }
}

internal readonly record struct AndroidQrFrame(double Scale, double CardOpacity, double CopyOpacity,
    double Mouth, double BodyScale, bool Complete);

internal static class AndroidQrTimeline
{
    internal static AndroidQrFrame At(double seconds, bool reduced)
    {
        static double Progress(double t, double start, double duration) => Math.Clamp((t - start) / duration, 0, 1);
        static double Smooth(double p) => p * p * (3 - 2 * p);
        if (reduced) return new(1, Progress(seconds, 0, .15), 1, 0, 1, seconds >= .15);
        var travel = Progress(seconds, .35, .55);
        var scale = .025 + .975 * (1 - Math.Pow(1 - travel, 3));
        var mouth = Smooth(Progress(seconds, .18, .17)) * (1 - Smooth(Progress(seconds, .85, .25)));
        var squash = 1 - .1 * Smooth(Progress(seconds, 0, .18)) + .1 * Smooth(Progress(seconds, .18, .72));
        return new(scale, Progress(seconds, .35, .08), Progress(seconds, .9, .2), mouth, squash, seconds >= 1.1);
    }
}

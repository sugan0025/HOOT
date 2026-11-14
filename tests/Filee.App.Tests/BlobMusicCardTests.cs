using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Filee.App.Services;
using Filee.App.Services.Triggers;
using Filee.App.Views;
using Filee.Core.Platform;

namespace Filee.App.Tests;

[Collection("Motion")]
public sealed class BlobMusicCardTests
{
    [AvaloniaFact]
    public async Task App_quip_survives_track_updates_and_restores_latest_track()
    {
        TestServices.EnsureInitialized();
        var window = new BlobCompanionWindow(AppHost.Get<RadialController>(), AppHost.Get<TriggerService>());
        try
        {
            window.Show();
            window.MusicDisplayEnabled = true;
            var session = new MusicSessionSnapshot("spotify", "First song", "Artist", MusicPlaybackState.Playing, true, true);
            window.SetMusicSession(session);
            Assert.True(window.ShowAppReaction("Browser time", "chrome"));
            window.SetMusicSession(session with { Title = "Second song" });
            await Task.Delay(400);
            Assert.True(window.FindControl<TextBlock>("ThoughtText")!.IsVisible);
            Assert.Equal("Browser time", window.FindControl<TextBlock>("ThoughtText")!.Text);
            Assert.False(window.FindControl<StackPanel>("TrackContent")!.IsVisible);
            await Task.Delay(6500);
            Assert.True(window.FindControl<StackPanel>("TrackContent")!.IsVisible);
            Assert.Equal("Second song", window.FindControl<TextBlock>("TrackTitle")!.Text);
        }
        finally { window.Close(); }
    }

    [AvaloniaFact]
    public void Missing_lyrics_and_collapsed_controls_reserve_no_extra_row()
    {
        TestServices.EnsureInitialized();
        var window = new BlobCompanionWindow(AppHost.Get<RadialController>(), AppHost.Get<TriggerService>());
        try
        {
            window.Show();
            window.MusicDisplayEnabled = true;
            window.SetMusicSession(new("spotify", "Song", "Artist", MusicPlaybackState.Paused, false, false));
            Assert.False(window.FindControl<StackPanel>("LyricsPanel")!.IsVisible);
            Assert.Equal(0, window.FindControl<StackPanel>("TrackButtons")!.Height);
            Assert.True(double.IsNaN(window.FindControl<Canvas>("MusicParticleCanvas")!.Height));
        }
        finally { window.Close(); }
    }
}

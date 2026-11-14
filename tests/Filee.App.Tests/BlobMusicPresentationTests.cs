using System.Text.Json;
using System.Text.Json.Serialization;
using Filee.App.Services;
using Filee.Core.Platform;
using Filee.Core.Settings;

namespace Filee.App.Tests;

public sealed class BlobMusicPresentationTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() },
    };

    [Fact]
    public void Music_card_is_shown_for_playing_or_paused_sessions_only()
    {
        Assert.True(BlobMusicPresentation.IsDisplayable(Session(MusicPlaybackState.Playing)));
        Assert.True(BlobMusicPresentation.IsDisplayable(Session(MusicPlaybackState.Paused)));
        Assert.False(BlobMusicPresentation.IsDisplayable(Session(MusicPlaybackState.Stopped)));
        Assert.False(BlobMusicPresentation.IsDisplayable(Session(MusicPlaybackState.Unknown)));
        Assert.False(BlobMusicPresentation.IsDisplayable(null));
    }

    [Fact]
    public void Missing_title_uses_fallback_and_artist_is_trimmed()
    {
        Assert.Equal("Music is playing", BlobMusicPresentation.Title(Session(MusicPlaybackState.Playing, "  ", " Artist ")));
        Assert.Equal("Artist", BlobMusicPresentation.Artist(Session(MusicPlaybackState.Playing, "Track", " Artist ")));
    }

    [Fact]
    public void Playback_position_advances_while_playing_and_clamps_to_track_end()
    {
        var sampledAt = DateTimeOffset.UtcNow;
        var playing = Session(MusicPlaybackState.Playing) with
        {
            Position = TimeSpan.FromSeconds(40),
            Duration = TimeSpan.FromSeconds(120),
            TimelineUpdatedAt = sampledAt,
        };
        Assert.Equal(TimeSpan.FromSeconds(45), BlobMusicPresentation.PositionAt(playing, sampledAt.AddSeconds(5)));
        Assert.Equal(TimeSpan.FromSeconds(120), BlobMusicPresentation.PositionAt(playing, sampledAt.AddSeconds(100)));

        var paused = playing with { State = MusicPlaybackState.Paused };
        Assert.Equal(TimeSpan.FromSeconds(40), BlobMusicPresentation.PositionAt(paused, sampledAt.AddSeconds(5)));
    }

    [Theory]
    [InlineData(0, "0:00")]
    [InlineData(65, "1:05")]
    [InlineData(3661, "1:01:01")]
    public void Track_time_has_compact_elapsed_format(int seconds, string expected) =>
        Assert.Equal(expected, BlobMusicPresentation.FormatTime(TimeSpan.FromSeconds(seconds)));

    [Fact]
    public void Music_setting_defaults_on_and_persists()
    {
        var defaults = JsonSerializer.Deserialize<AppSettings>("{}", JsonOptions)!;
        Assert.True(defaults.BlobCompanion.MusicDisplayEnabled);

        defaults.BlobCompanion.MusicDisplayEnabled = false;
        var restored = JsonSerializer.Deserialize<AppSettings>(JsonSerializer.Serialize(defaults, JsonOptions), JsonOptions)!;
        Assert.False(restored.BlobCompanion.MusicDisplayEnabled);
    }

    private static MusicSessionSnapshot Session(MusicPlaybackState state, string? title = "Track", string? artist = "Artist") =>
        new("spotify", title, artist, state, CanSkipPrevious: true, CanSkipNext: true);
}

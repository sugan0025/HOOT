using Filee.Core.Platform;
using Filee.Platform.Windows;

namespace Filee.Platform.Tests;

public sealed class WindowsMusicSessionSelectionTests
{
    [Theory]
    [InlineData("Spotify.exe", "spotify")]
    [InlineData("com.spotify.client", "spotify")]
    [InlineData("AmazonMusic", "amazon-music")]
    [InlineData("com.amazon.music", "amazon-music")]
    [InlineData("Chrome", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void Player_identity_matches_only_supported_desktop_players(string? source, string? expected) =>
        Assert.Equal(expected, WindowsMusicSessionSelection.PlayerFor(source));

    [Fact]
    public void Playing_session_beats_paused_even_when_paused_is_current()
    {
        var pausedCurrent = Candidate("spotify", MusicPlaybackState.Paused, current: true, index: 0);
        var playing = Candidate("amazon-music", MusicPlaybackState.Playing, current: false, index: 1);

        Assert.Same(playing, WindowsMusicSessionSelection.Select([pausedCurrent, playing]));
    }

    [Fact]
    public void Current_session_wins_among_sessions_that_are_playing()
    {
        var other = Candidate("spotify", MusicPlaybackState.Playing, current: false, index: 0);
        var current = Candidate("amazon-music", MusicPlaybackState.Playing, current: true, index: 1);

        Assert.Same(current, WindowsMusicSessionSelection.Select([other, current]));
    }

    [Fact]
    public void Current_paused_session_is_selected_when_nothing_is_playing()
    {
        var other = Candidate("spotify", MusicPlaybackState.Paused, current: false, index: 0);
        var current = Candidate("amazon-music", MusicPlaybackState.Paused, current: true, index: 1);

        Assert.Same(current, WindowsMusicSessionSelection.Select([other, current]));
    }

    [Fact]
    public void Stopped_or_unknown_sessions_are_not_displayed()
    {
        var candidates = new[]
        {
            Candidate("spotify", MusicPlaybackState.Stopped, current: true, index: 0),
            Candidate("amazon-music", MusicPlaybackState.Unknown, current: false, index: 1),
        };

        Assert.Null(WindowsMusicSessionSelection.Select(candidates));
    }

    private static WindowsMusicSessionCandidate Candidate(string player, MusicPlaybackState state, bool current, int index) =>
        new(new MusicSessionSnapshot(player, "Title", "Artist", state, true, true), current, index);
}

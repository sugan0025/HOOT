using Filee.Core.Platform;

namespace Filee.App.Services;

/// <summary>Small, testable presentation rules for the companion's transient track card.</summary>
internal static class BlobMusicPresentation
{
    public const string MissingMetadataText = "Music is playing";

    public static bool IsDisplayable(MusicSessionSnapshot? session) =>
        session?.State is MusicPlaybackState.Playing or MusicPlaybackState.Paused;

    public static string Title(MusicSessionSnapshot session) =>
        string.IsNullOrWhiteSpace(session.Title) ? MissingMetadataText : session.Title.Trim();

    public static string Artist(MusicSessionSnapshot session) => session.Artist?.Trim() ?? "";

    public static TimeSpan PositionAt(MusicSessionSnapshot session, DateTimeOffset now)
    {
        var position = session.Position;
        if (session.State == MusicPlaybackState.Playing && session.TimelineUpdatedAt != default)
            position += now - session.TimelineUpdatedAt;
        if (position < TimeSpan.Zero)
            return TimeSpan.Zero;
        return session.Duration > TimeSpan.Zero && position > session.Duration ? session.Duration : position;
    }

    public static string FormatTime(TimeSpan time)
    {
        var totalSeconds = Math.Max(0, (long)time.TotalSeconds);
        return totalSeconds >= 3600
            ? $"{totalSeconds / 3600}:{(totalSeconds / 60) % 60:00}:{totalSeconds % 60:00}"
            : $"{totalSeconds / 60}:{totalSeconds % 60:00}";
    }
}

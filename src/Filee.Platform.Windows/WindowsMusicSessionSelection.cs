using Filee.Core.Platform;

namespace Filee.Platform.Windows;

internal sealed record WindowsMusicSessionCandidate(MusicSessionSnapshot Snapshot, bool IsCurrent, int SessionIndex);

internal static class WindowsMusicSessionSelection
{
    public static WindowsMusicSessionCandidate? Select(IEnumerable<WindowsMusicSessionCandidate> candidates)
    {
        return candidates
            .Where(candidate => candidate.Snapshot.State is MusicPlaybackState.Playing or MusicPlaybackState.Paused)
            .OrderBy(candidate => candidate.Snapshot.State == MusicPlaybackState.Playing ? 0 : 1)
            .ThenBy(candidate => candidate.IsCurrent ? 0 : 1)
            .ThenBy(candidate => candidate.Snapshot.Player == "spotify" ? 0 : 1)
            .FirstOrDefault();
    }

    public static string? PlayerFor(string? sourceAppUserModelId)
    {
        if (string.IsNullOrWhiteSpace(sourceAppUserModelId)) return null;
        var source = new string(sourceAppUserModelId.Where(char.IsLetterOrDigit).Select(char.ToLowerInvariant).ToArray());
        if (source.Contains("spotify", StringComparison.Ordinal)) return "spotify";
        if (source.Contains("amazonmusic", StringComparison.Ordinal)) return "amazon-music";
        return null;
    }
}

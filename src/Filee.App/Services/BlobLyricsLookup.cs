using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Filee.Core.Platform;

namespace Filee.App.Services;

/// <summary>Transient LRCLIB result; lyrics are held in memory only and never logged or persisted.</summary>
internal sealed record BlobLyricsResult(IReadOnlyList<TimedLyricLine> SyncedLines, IReadOnlyList<string> PlainLines);
internal sealed record TimedLyricLine(TimeSpan At, string Text);

internal static partial class BlobLyricsLookup
{
    private static readonly HttpClient Client = CreateClient();

    public static async Task<BlobLyricsResult?> LookupAsync(MusicSessionSnapshot session, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(session.Title) || string.IsNullOrWhiteSpace(session.Artist))
            return null;

        var query = $"track_name={Uri.EscapeDataString(session.Title)}&artist_name={Uri.EscapeDataString(session.Artist)}";
        if (!string.IsNullOrWhiteSpace(session.Album))
            query += $"&album_name={Uri.EscapeDataString(session.Album)}";
        if (session.Duration is { TotalSeconds: >= 1 and <= 3600 })
            query += $"&duration={(int)Math.Round(session.Duration.TotalSeconds, MidpointRounding.AwayFromZero)}";

        using var response = await Client.GetAsync($"api/get?{query}", HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (response.StatusCode == HttpStatusCode.NotFound)
            return null;
        response.EnsureSuccessStatusCode();

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        var record = await JsonSerializer.DeserializeAsync<LyricsRecord>(stream, JsonOptions, cancellationToken).ConfigureAwait(false);
        if (record is null || record.Instrumental)
            return null;

        var synced = ParseSyncedLyrics(record.SyncedLyrics);
        var plain = string.IsNullOrWhiteSpace(record.PlainLyrics)
            ? Array.Empty<string>()
            : record.PlainLyrics.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        return synced.Count == 0 && plain.Length == 0 ? null : new BlobLyricsResult(synced, plain);
    }

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { BaseAddress = new Uri("https://lrclib.net/"), Timeout = TimeSpan.FromSeconds(8) };
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("HOOT", "2.6.4"));
        client.DefaultRequestHeaders.UserAgent.TryParseAdd("(https://github.com/sugan0025/HOOT)");
        return client;
    }

    private static IReadOnlyList<TimedLyricLine> ParseSyncedLyrics(string? lyrics)
    {
        if (string.IsNullOrWhiteSpace(lyrics))
            return [];

        var result = new List<TimedLyricLine>();
        foreach (var rawLine in lyrics.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            var matches = TimestampPattern().Matches(rawLine);
            if (matches.Count == 0)
                continue; // Ignore LRC metadata such as [ar:], [ti:], and [offset:].
            var text = TimestampPattern().Replace(rawLine, string.Empty).Trim();
            if (text.Length == 0)
                continue;
            foreach (Match match in matches)
            {
                if (!int.TryParse(match.Groups["minutes"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var minutes)
                    || !int.TryParse(match.Groups["seconds"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var seconds))
                    continue;
                var fraction = match.Groups["fraction"].Value;
                var milliseconds = fraction.Length == 0 ? 0
                    : int.Parse(fraction.PadRight(3, '0')[..3], CultureInfo.InvariantCulture);
                result.Add(new TimedLyricLine(TimeSpan.FromMinutes(minutes) + TimeSpan.FromSeconds(seconds)
                    + TimeSpan.FromMilliseconds(milliseconds), text));
            }
        }
        return result.OrderBy(line => line.At).ToArray();
    }

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    [GeneratedRegex(@"\[(?<minutes>\d{1,3}):(?<seconds>\d{2})(?:[.,](?<fraction>\d{1,3}))?\]", RegexOptions.CultureInvariant)]
    private static partial Regex TimestampPattern();

    private sealed record LyricsRecord(
        [property: JsonPropertyName("instrumental")] bool Instrumental,
        [property: JsonPropertyName("plainLyrics")] string? PlainLyrics,
        [property: JsonPropertyName("syncedLyrics")] string? SyncedLyrics);
}

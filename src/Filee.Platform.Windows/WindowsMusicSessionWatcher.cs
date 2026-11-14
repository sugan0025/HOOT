// Local, Windows-native playback metadata and transport controls for the desktop Blob.

using System.Diagnostics;
using System.Runtime.Versioning;
using Windows.Foundation;
using Windows.Media.Control;
using Filee.Core.Platform;

namespace Filee.Platform.Windows;

[SupportedOSPlatform("windows10.0.17763.0")]
internal sealed class WindowsMusicSessionWatcher : IDisposable
{
    private readonly Action<MusicSessionSnapshot?> _changed;
    private readonly SemaphoreSlim _refreshLock = new(1, 1);
    private readonly Dictionary<GlobalSystemMediaTransportControlsSession, SessionSubscription> _subscriptions = [];
    private readonly TypedEventHandler<GlobalSystemMediaTransportControlsSessionManager, SessionsChangedEventArgs> _sessionsChanged;
    private readonly TypedEventHandler<GlobalSystemMediaTransportControlsSessionManager, CurrentSessionChangedEventArgs> _currentSessionChanged;
    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private GlobalSystemMediaTransportControlsSession? _selectedSession;
    private bool _disposed;

    internal WindowsMusicSessionWatcher(Action<MusicSessionSnapshot?> changed)
    {
        _changed = changed;
        _sessionsChanged = (_, _) => QueueRefresh();
        _currentSessionChanged = (_, _) => QueueRefresh();
        _ = InitializeAsync();
    }

    public static IDisposable Start(Action<MusicSessionSnapshot?> changed) => new WindowsMusicSessionWatcher(changed);

    public Task<bool> SkipAsync(string player, bool next)
    {
        var session = _selectedSession;
        if (_disposed || session is null || !string.Equals(WindowsMusicSessionSelection.PlayerFor(session.SourceAppUserModelId), player, StringComparison.Ordinal))
            return Task.FromResult(false);
        return SkipCoreAsync(session, next);
    }

    private static async Task<bool> SkipCoreAsync(GlobalSystemMediaTransportControlsSession session, bool next)
    {
        try
        {
            var controls = session.GetPlaybackInfo()?.Controls;
            if (controls is null || (next ? !controls.IsNextEnabled : !controls.IsPreviousEnabled))
                return false;
            return next ? await session.TrySkipNextAsync() : await session.TrySkipPreviousAsync();
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return false;
        }
    }

    private async Task InitializeAsync()
    {
        try
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            if (_disposed)
                return;
            _manager = manager;
            manager.SessionsChanged += _sessionsChanged;
            manager.CurrentSessionChanged += _currentSessionChanged;
            await RefreshAsync();
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            Trace.TraceInformation("Windows media sessions are unavailable: {0}", ex.Message);
            Publish(null);
        }
    }

    private void QueueRefresh()
    {
        if (!_disposed)
            _ = RefreshSafelyAsync();
    }

    private async Task RefreshSafelyAsync()
    {
        try { await RefreshAsync(); }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            Trace.TraceInformation("Reading the current Windows media session failed: {0}", ex.Message);
        }
    }

    private async Task RefreshAsync()
    {
        var manager = _manager;
        if (_disposed || manager is null)
            return;

        await _refreshLock.WaitAsync();
        try
        {
            if (_disposed)
                return;
            var sessions = manager.GetSessions();
            var supported = sessions
                .Select(session => (Session: session, Player: WindowsMusicSessionSelection.PlayerFor(session.SourceAppUserModelId)))
                .Where(item => item.Player is not null)
                .ToArray();
            SyncSubscriptions(supported.Select(item => item.Session));

            var current = manager.GetCurrentSession();
            var candidates = new List<WindowsMusicSessionCandidate>();
            for (var index = 0; index < supported.Length; index++)
            {
                if (_disposed)
                    return;
                var item = supported[index];
                try
                {
                    var playback = item.Session.GetPlaybackInfo();
                    var state = playback?.PlaybackStatus switch
                    {
                        GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing => MusicPlaybackState.Playing,
                        GlobalSystemMediaTransportControlsSessionPlaybackStatus.Paused => MusicPlaybackState.Paused,
                        GlobalSystemMediaTransportControlsSessionPlaybackStatus.Stopped => MusicPlaybackState.Stopped,
                        _ => MusicPlaybackState.Unknown,
                    };
                    var controls = playback?.Controls;
                    var media = await item.Session.TryGetMediaPropertiesAsync();
                    var timeline = item.Session.GetTimelineProperties();
                    var duration = timeline.EndTime - timeline.StartTime;
                    if (duration < TimeSpan.Zero) duration = TimeSpan.Zero;
                    candidates.Add(new WindowsMusicSessionCandidate(
                        new MusicSessionSnapshot(item.Player!, media?.Title, media?.Artist, state,
                            controls?.IsPreviousEnabled == true, controls?.IsNextEnabled == true,
                            timeline.Position, duration, timeline.LastUpdatedTime, media?.AlbumTitle),
                        Equals(item.Session, current), index));
                }
                catch (Exception ex) when (ex is not OutOfMemoryException)
                {
                    // A player may withdraw its session while its metadata is being read.
                    Trace.TraceInformation("A supported media session changed during refresh: {0}", ex.Message);
                }
            }

            var selected = WindowsMusicSessionSelection.Select(candidates);
            _selectedSession = selected is null ? null : supported[selected.SessionIndex].Session;
            Publish(selected?.Snapshot);
        }
        finally
        {
            _refreshLock.Release();
        }
    }

    private void SyncSubscriptions(IEnumerable<GlobalSystemMediaTransportControlsSession> sessions)
    {
        var next = sessions.ToHashSet();
        foreach (var stale in _subscriptions.Keys.Where(session => !next.Contains(session)).ToArray())
        {
            var subscription = _subscriptions[stale];
            stale.PlaybackInfoChanged -= subscription.PlaybackChanged;
            stale.MediaPropertiesChanged -= subscription.MediaChanged;
            stale.TimelinePropertiesChanged -= subscription.TimelineChanged;
            _subscriptions.Remove(stale);
        }
        foreach (var session in next)
        {
            if (_subscriptions.ContainsKey(session))
                continue;
            TypedEventHandler<GlobalSystemMediaTransportControlsSession, PlaybackInfoChangedEventArgs> playback = (_, _) => QueueRefresh();
            TypedEventHandler<GlobalSystemMediaTransportControlsSession, MediaPropertiesChangedEventArgs> media = (_, _) => QueueRefresh();
            TypedEventHandler<GlobalSystemMediaTransportControlsSession, TimelinePropertiesChangedEventArgs> timeline = (_, _) => QueueRefresh();
            session.PlaybackInfoChanged += playback;
            session.MediaPropertiesChanged += media;
            session.TimelinePropertiesChanged += timeline;
            _subscriptions.Add(session, new SessionSubscription(playback, media, timeline));
        }
    }

    private void Publish(MusicSessionSnapshot? snapshot)
    {
        if (_disposed)
            return;
        try { _changed(snapshot); }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            Trace.TraceInformation("Publishing the current media session failed: {0}", ex.Message);
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_manager is { } manager)
        {
            manager.SessionsChanged -= _sessionsChanged;
            manager.CurrentSessionChanged -= _currentSessionChanged;
        }
        foreach (var (session, subscription) in _subscriptions)
        {
            session.PlaybackInfoChanged -= subscription.PlaybackChanged;
            session.MediaPropertiesChanged -= subscription.MediaChanged;
            session.TimelinePropertiesChanged -= subscription.TimelineChanged;
        }
        _subscriptions.Clear();
        _selectedSession = null;
        _manager = null;
    }

    private sealed record SessionSubscription(
        TypedEventHandler<GlobalSystemMediaTransportControlsSession, PlaybackInfoChangedEventArgs> PlaybackChanged,
        TypedEventHandler<GlobalSystemMediaTransportControlsSession, MediaPropertiesChangedEventArgs> MediaChanged,
        TypedEventHandler<GlobalSystemMediaTransportControlsSession, TimelinePropertiesChangedEventArgs> TimelineChanged);
}

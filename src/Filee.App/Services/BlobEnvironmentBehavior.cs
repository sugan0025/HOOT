using Filee.Core.Platform;

namespace Filee.App.Services;

internal enum BlobEnvironmentReaction { Welcome, Charging, LowBattery, WindowMoved }

/// <summary>Transient edge detection. First samples establish baselines without greeting stale events.</summary>
internal sealed class BlobEnvironmentBehavior
{
    private bool? _charging;
    private bool _lowBattery;
    private int? _lastBattery;
    private bool _wasIdle;
    private long _window;
    private PlatformScreenBounds? _bounds;
    private DateTime _windowChanged;
    private bool _windowPending;
    private DateTime _lastWelcome, _lastCharge, _lastWindow;

    public void ResetObservation()
    {
        _charging = null;
        _lastBattery = null;
        _window = 0;
        _bounds = null;
        _windowPending = false;
        _wasIdle = false;
    }

    public BlobEnvironmentReaction? Update(CompanionEnvironmentSnapshot? snapshot, TimeSpan? idle, DateTime now,
        bool powerEnabled, bool windowsEnabled)
    {
        BlobEnvironmentReaction? result = null;
        if (idle >= TimeSpan.FromMinutes(5)) _wasIdle = true;
        else if (idle is { } elapsed && elapsed < TimeSpan.FromSeconds(3) && _wasIdle)
        {
            _wasIdle = false;
            if (now - _lastWelcome >= TimeSpan.FromMinutes(10)) { _lastWelcome = now; result = BlobEnvironmentReaction.Welcome; }
        }
        if (snapshot is null) return result;
        if (powerEnabled && snapshot.Charging == true && _charging == false && now - _lastCharge >= TimeSpan.FromMinutes(5))
        { _lastCharge = now; result ??= BlobEnvironmentReaction.Charging; }
        _charging = snapshot.Charging;
        if (snapshot.BatteryPercent > 25) _lowBattery = false;
        if (snapshot.Charging == false && snapshot.BatteryPercent is < 20 && _lastBattery is >= 20 && !_lowBattery)
        {
            _lowBattery = true;
            if (powerEnabled) result ??= BlobEnvironmentReaction.LowBattery;
        }
        _lastBattery = snapshot.BatteryPercent;
        if (snapshot.WindowHandle != _window || _bounds is null)
        { _window = snapshot.WindowHandle; _bounds = snapshot.WindowBounds; _windowPending = false; }
        else if (snapshot.WindowBounds is { } current && _bounds is { } previous
            && (Math.Abs(current.X - previous.X) + Math.Abs(current.Y - previous.Y)
                + Math.Abs(current.Width - previous.Width) + Math.Abs(current.Height - previous.Height)) > 8)
        { _bounds = current; _windowChanged = now; _windowPending = true; }
        else if (_windowPending && now - _windowChanged >= TimeSpan.FromMilliseconds(400))
        {
            _windowPending = false;
            if (windowsEnabled && now - _lastWindow >= TimeSpan.FromSeconds(60))
            { _lastWindow = now; result ??= BlobEnvironmentReaction.WindowMoved; }
        }
        return result;
    }
}

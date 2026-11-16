using Avalonia;

namespace Filee.App.Services;

/// <summary>Deterministic timing and bounds rules for the optional companion-only 3D animations.</summary>
internal static class Blob3DCompanionTimelines
{
    public const int TravelHopFrames = 18;
    public const int FrameIntervalMilliseconds = 33;
    public const int TravelPauseMilliseconds = 450;
    public const double TurnDurationSeconds = 1.6;

    public static double TurnAngle(double progress)
    {
        var t = Math.Clamp(progress, 0, 1);
        var eased = t * t * (3 - 2 * t);
        return Math.Tau * eased;
    }

    /// <summary>One eased hop leg, in physical pixels, clamped to the monitor work area.</summary>
    public static PixelPoint TravelHopPosition(PixelPoint from, PixelPoint to, PixelRect workArea,
        int windowWidth, int windowHeight, double scale, double progress)
    {
        var t = Math.Clamp(progress, 0, 1);
        var eased = t * t * (3 - 2 * t);
        var arc = Math.Sin(Math.PI * t) * 24 * Math.Max(1, scale);
        var maxX = Math.Max(workArea.X, workArea.Right - Math.Max(0, windowWidth));
        var maxY = Math.Max(workArea.Y, workArea.Bottom - Math.Max(0, windowHeight));
        var x = (int)Math.Round(from.X + (to.X - from.X) * eased);
        var y = (int)Math.Round(from.Y + (to.Y - from.Y) * eased - arc);
        return new PixelPoint(Math.Clamp(x, workArea.X, maxX), Math.Clamp(y, workArea.Y, maxY));
    }

    /// <summary>Pick a clamped destination in the preferred direction, reversing if that edge leaves no room.</summary>
    public static PixelPoint TravelHopDestination(PixelPoint from, int horizontalDelta, int verticalDelta,
        PixelRect workArea, int windowWidth, int windowHeight)
    {
        PixelPoint Clamp(int dx) => new(
            Math.Clamp(from.X + dx, workArea.X, Math.Max(workArea.X, workArea.Right - Math.Max(0, windowWidth))),
            Math.Clamp(from.Y + verticalDelta, workArea.Y, Math.Max(workArea.Y, workArea.Bottom - Math.Max(0, windowHeight))));

        var destination = Clamp(horizontalDelta);
        return destination == from ? Clamp(-horizontalDelta) : destination;
    }
}

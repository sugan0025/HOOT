using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Filee.App.Controls;
using Filee.App.Services;
using Filee.App.Services.Triggers;
using Filee.App.Views;
using Filee.Core.Platform;
using Xunit;

namespace Filee.App.Tests;

public sealed class BlobEnvironmentBehaviorTests
{

    [AvaloniaFact]
    public void Password_focus_still_blocks_field_gaze_without_accessories_or_quips()
    {
        TestServices.EnsureInitialized();
        var window = new BlobCompanionWindow(AppHost.Get<RadialController>(), AppHost.Get<TriggerService>());
        try
        {
            var blob = window.FindControl<BlobatarControl>("Blob")!;
            var thoughtBefore = window.FindControl<TextBlock>("ThoughtText")!.Text;
            window.SetFocusedInputContext(new FocusedInputContext(null, true));
            window.SetSystemIdleTime(TimeSpan.FromMinutes(1));
            Assert.True(blob.State.PrivacyFocus);
            Assert.Equal(thoughtBefore, window.FindControl<TextBlock>("ThoughtText")!.Text);

            window.SetFocusedInputContext(new FocusedInputContext(new PlatformScreenBounds(10, 10, 120, 28), false));
            Assert.False(blob.State.PrivacyFocus);
        }
        finally { window.Close(); }
    }

    [Fact]
    public void First_environment_sample_establishes_baseline_without_false_power_reaction()
    {
        var behavior = new BlobEnvironmentBehavior();
        var sample = new CompanionEnvironmentSnapshot(12, new(0, 0, 800, 600), true, 100);
        Assert.Null(behavior.Update(sample, TimeSpan.Zero, DateTime.UtcNow, true, true));
    }

    [Fact]
    public void Charging_edge_reacts_once_and_respects_cooldown()
    {
        var behavior = new BlobEnvironmentBehavior();
        var now = DateTime.UtcNow;
        var sample = new CompanionEnvironmentSnapshot(12, new(0, 0, 800, 600), false, 60);
        Assert.Null(behavior.Update(sample, TimeSpan.Zero, now, true, true));
        Assert.Equal(BlobEnvironmentReaction.Charging,
            behavior.Update(sample with { Charging = true }, TimeSpan.Zero, now.AddSeconds(1), true, true));
        Assert.Null(behavior.Update(sample with { Charging = false }, TimeSpan.Zero, now.AddSeconds(2), true, true));
        Assert.Null(behavior.Update(sample with { Charging = true }, TimeSpan.Zero, now.AddSeconds(3), true, true));
        Assert.Null(behavior.Update(sample with { Charging = false }, TimeSpan.Zero, now.AddMinutes(6), true, true));
        Assert.Equal(BlobEnvironmentReaction.Charging,
            behavior.Update(sample with { Charging = true }, TimeSpan.Zero, now.AddMinutes(6).AddSeconds(1), true, true));
    }

    [Fact]
    public void Low_battery_reacts_only_when_crossing_threshold_and_resets_after_recovery()
    {
        var behavior = new BlobEnvironmentBehavior();
        var now = DateTime.UtcNow;
        var sample = new CompanionEnvironmentSnapshot(12, null, false, 22);
        behavior.Update(sample, TimeSpan.Zero, now, true, true);
        Assert.Equal(BlobEnvironmentReaction.LowBattery,
            behavior.Update(sample with { BatteryPercent = 19 }, TimeSpan.Zero, now.AddSeconds(1), true, true));
        Assert.Null(behavior.Update(sample with { BatteryPercent = 18 }, TimeSpan.Zero, now.AddSeconds(2), true, true));
        behavior.Update(sample with { BatteryPercent = 26 }, TimeSpan.Zero, now.AddSeconds(3), true, true);
        Assert.Equal(BlobEnvironmentReaction.LowBattery,
            behavior.Update(sample with { BatteryPercent = 19 }, TimeSpan.Zero, now.AddSeconds(4), true, true));
    }

    [Fact]
    public void Window_motion_waits_until_geometry_stabilizes_and_obeys_cooldown()
    {
        var behavior = new BlobEnvironmentBehavior();
        var now = DateTime.UtcNow;
        var sample = new CompanionEnvironmentSnapshot(12, new(0, 0, 800, 600), false, 80);
        behavior.Update(sample, TimeSpan.Zero, now, true, true);
        Assert.Null(behavior.Update(sample with { WindowBounds = new(10, 0, 800, 600) }, TimeSpan.Zero, now.AddSeconds(1), true, true));
        Assert.Null(behavior.Update(sample with { WindowBounds = new(10, 0, 800, 600) }, TimeSpan.Zero, now.AddMilliseconds(1399), true, true));
        Assert.Equal(BlobEnvironmentReaction.WindowMoved, behavior.Update(sample with { WindowBounds = new(10, 0, 800, 600) }, TimeSpan.Zero, now.AddMilliseconds(1400), true, true));
        Assert.Null(behavior.Update(sample with { WindowBounds = new(20, 0, 800, 600) }, TimeSpan.Zero, now.AddSeconds(2), true, true));
        Assert.Null(behavior.Update(sample with { WindowBounds = new(20, 0, 800, 600) }, TimeSpan.Zero, now.AddSeconds(3), true, true));
    }

    [Fact]
    public void Password_context_never_drives_typing_gaze_even_during_recent_input()
    {
        var bounds = new PlatformScreenBounds(5, 6, 100, 20);
        Assert.False(BlobFocusGaze.ShouldFollow(new FocusedInputContext(null, true), TimeSpan.Zero));
        Assert.False(BlobFocusGaze.ShouldFollow(new FocusedInputContext(bounds, false), TimeSpan.FromSeconds(4)));
        Assert.True(BlobFocusGaze.ShouldFollow(new FocusedInputContext(bounds, false), TimeSpan.FromSeconds(2)));
        Assert.False(BlobFocusGaze.ShouldFollow(null, TimeSpan.Zero));
    }
}

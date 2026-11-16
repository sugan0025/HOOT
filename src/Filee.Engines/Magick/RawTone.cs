// LibRaw writes camera RAW pixels with the BT.709 curve; ImageMagick exposes those bytes as sRGB without changing the
// curve. Convert once on read so later color transforms and encoders see the correct standard sRGB values.

using ImageMagick;

namespace Filee.Engines.Magick;

internal static class RawTone
{
    private static readonly Lazy<byte[]> Table = new(BuildTable);

    internal static byte[] Bt709ToSrgbTable() => (byte[])Table.Value.Clone();

    private static byte[] BuildTable()
    {
        var table = new byte[256];
        for (var i = 0; i < table.Length; i++)
        {
            var value = i / 255.0;
            var linear = value < 0.081 ? value / 4.5 : Math.Pow((value + 0.099) / 1.099, 1 / 0.45);
            var srgb = linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.Pow(linear, 1 / 2.4) - 0.055;
            table[i] = (byte)Math.Clamp((int)Math.Round(srgb * 255.0), 0, 255);
        }
        return table;
    }

    internal static void ToSrgb(MagickImageCollection images)
    {
        foreach (var image in images)
        {
            var pixels = image.GetPixels().ToByteArray(PixelMapping.RGBA);
            if (pixels is null)
                continue;
            var table = Table.Value;
            for (var i = 0; i < pixels.Length; i += 4)
            {
                pixels[i] = table[pixels[i]];
                pixels[i + 1] = table[pixels[i + 1]];
                pixels[i + 2] = table[pixels[i + 2]];
            }
            image.ImportPixels(pixels, new PixelImportSettings(image.Width, image.Height, StorageType.Char, PixelMapping.RGBA));
        }
    }
}

// Adds Annotate's marks to PDF pages as PDF drawing operators, leaving page text and images as vector/original objects.

using Filee.Core.Presets;
using Filee.Engines.Annotations;
using Filee.Engines.Vector;
using PdfSharp;
using PdfSharp.Drawing;
using PdfSharp.Pdf;

namespace Filee.Engines.Pdf;

/// <summary>A mark on a page in the PDF editor, with the page index in the output document.</summary>
public sealed record PdfPageMark(int PageIndex, Annotation Mark);

/// <summary>Writes editable page marks as vector paths and text instead of rasterising the PDF page.</summary>
public static class PdfVectorAnnotationEditor
{
    /// <summary>
    /// Appends marks in displayed, top-left-normalised page coordinates. The input PDF is preserved on failure and
    /// only replaced after PDFsharp has successfully written a complete copy.
    /// </summary>
    public static void Apply(string path, IReadOnlyList<PdfPageMark> marks, CancellationToken cancellationToken = default)
    {
        if (marks.Count == 0)
            return;

        var fullPath = Path.GetFullPath(path);
        var temporary = fullPath + ".hoot-editing";
        try
        {
            PdfSharpConverter.CopyWritable(fullPath, temporary);
            using (var document = PdfSharpConverter.OpenForChanges(temporary))
            {
                foreach (var group in marks.GroupBy(mark => mark.PageIndex).OrderBy(group => group.Key))
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (group.Key < 0 || group.Key >= document.PageCount)
                        throw new ArgumentOutOfRangeException(nameof(marks), $"PDF page {group.Key + 1} does not exist.");
                    Draw(document.Pages[group.Key], group.Select(mark => mark.Mark));
                }
                document.Save(temporary);
            }
            File.Move(temporary, fullPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary))
                File.Delete(temporary);
        }
    }

    private static void Draw(PdfPage page, IEnumerable<Annotation> marks)
    {
        var media = page.MediaBox;
        var crop = page.Elements.ContainsKey("/CropBox") ? page.CropBox : media;
        var left = Math.Max(crop.X1, media.X1) - media.X1;
        var top = media.Y2 - Math.Min(crop.Y2, media.Y2);
        var width = Math.Min(crop.X2, media.X2) - Math.Max(crop.X1, media.X1);
        var height = Math.Min(crop.Y2, media.Y2) - Math.Max(crop.Y1, media.Y1);
        if (width <= 0 || height <= 0)
            return;

        var rotation = page.Rotate;
        var orientation = page.Orientation;
        page.Rotate = 0;
        page.Orientation = media.Width > media.Height ? PageOrientation.Landscape : PageOrientation.Portrait;
        try
        {
            using var graphics = XGraphics.FromPdfPage(page, XGraphicsPdfPageOptions.Append);
            using var glyphs = new GlyphOutlines(perTypeface: true);
            graphics.TranslateTransform(left, top);
            var (shownWidth, shownHeight) = (((rotation % 360) + 360) % 360) switch
            {
                90 => Turn(graphics, new XMatrix(0, -1, 1, 0, 0, height), height, width),
                180 => Turn(graphics, new XMatrix(-1, 0, 0, -1, width, height), width, height),
                270 => Turn(graphics, new XMatrix(0, 1, -1, 0, width, 0), height, width),
                _ => (width, height),
            };
            foreach (var mark in marks)
                DrawMark(graphics, glyphs, mark, shownWidth, shownHeight);
        }
        finally
        {
            page.Orientation = orientation;
            page.Rotate = rotation;
        }
    }

    private static (double Width, double Height) Turn(XGraphics graphics, XMatrix matrix, double width, double height)
    {
        graphics.MultiplyTransform(matrix);
        return (width, height);
    }

    private static void DrawMark(XGraphics graphics, GlyphOutlines glyphs, Annotation mark, double width, double height)
    {
        if (mark.Points.Count == 0 || mark.Kind == AnnotationKind.Unknown)
            return;
        var color = AnnotationPainter.ColorOf(mark.Color);
        var ink = XColor.FromArgb(color.Alpha, color.Red, color.Green, color.Blue);
        var stroke = AnnotationPainter.StrokeOf(mark.Width, (float)width, (float)height);
        var pen = new XPen(ink, stroke) { LineCap = XLineCap.Round, LineJoin = XLineJoin.Round };
        var points = mark.Points.Select(point => new XPoint(point.X * width, point.Y * height)).ToArray();

        switch (mark.Kind)
        {
            case AnnotationKind.Pen when points.Length >= 2:
                var path = new XGraphicsPath();
                {
                    path.StartFigure();
                    for (var index = 1; index < points.Length; index++)
                        path.AddLine(points[index - 1], points[index]);
                    graphics.DrawPath(pen, path);
                }
                break;
            case AnnotationKind.Arrow when points.Length >= 2:
                DrawArrow(graphics, points[0], points[1], ink, stroke);
                break;
            case AnnotationKind.Box when points.Length >= 2:
                graphics.DrawRoundedRectangle(pen, Rect(points[0], points[1]), new XSize(stroke * 1.2, stroke * 1.2));
                break;
            case AnnotationKind.Highlight when points.Length >= 2:
                var brush = new XSolidBrush(XColor.FromArgb(AnnotationPainter.HighlightAlpha, color.Red, color.Green, color.Blue));
                {
                    graphics.DrawRectangle(brush, Rect(points[0], points[1]));
                }
                break;
            case AnnotationKind.Text when !string.IsNullOrWhiteSpace(mark.Text):
                DrawText(graphics, glyphs, mark.Text!, points[0], width, height, ink, mark.Width);
                break;
            case AnnotationKind.Number:
                DrawNumber(graphics, glyphs, mark, points[0], width, height, ink);
                break;
        }
    }

    private static void DrawArrow(XGraphics graphics, XPoint tail, XPoint tip, XColor color, double stroke)
    {
        var dx = tip.X - tail.X;
        var dy = tip.Y - tail.Y;
        var length = Math.Sqrt(dx * dx + dy * dy);
        if (length < .5)
            return;
        var ux = dx / length;
        var uy = dy / length;
        var headLength = Math.Min(stroke * 4.5, length * .5);
        var headHalf = headLength * .58;
        var neck = new XPoint(tip.X - ux * headLength, tip.Y - uy * headLength);
        var line = new XPen(color, stroke) { LineCap = XLineCap.Round };
        {
            graphics.DrawLine(line, tail, new XPoint(neck.X + ux * stroke * .5, neck.Y + uy * stroke * .5));
        }
        var path = new XGraphicsPath();
        path.AddLine(tip, new XPoint(neck.X - uy * headHalf, neck.Y + ux * headHalf));
        path.AddLine(new XPoint(neck.X - uy * headHalf, neck.Y + ux * headHalf), new XPoint(neck.X + uy * headHalf, neck.Y - ux * headHalf));
        path.AddLine(new XPoint(neck.X + uy * headHalf, neck.Y - ux * headHalf), tip);
        path.CloseFigure();
        var fill = new XSolidBrush(color);
        graphics.DrawPath(null, fill, path);
    }

    private static void DrawText(XGraphics graphics, GlyphOutlines glyphs, string text, XPoint at, double width, double height, XColor color, AnnotationWidth weight)
    {
        var size = AnnotationPainter.FontSizeOf(weight, (float)width, (float)height);
        using var outline = PdfFinishing.Outline(glyphs, text, size, bold: true);
        if (outline.IsEmpty)
            return;
        var bounds = outline.TightBounds;
        var rect = new XRect(at.X, at.Y, bounds.Width + size * .9, Math.Max(size * 1.5, bounds.Height + size * .3));
        var fill = new XSolidBrush(color);
        graphics.DrawRoundedRectangle(fill, rect, new XSize(size * .3, size * .3));
        var luminance = .2126 * color.R + .7152 * color.G + .0722 * color.B;
        var textBrush = new XSolidBrush(luminance > 170 ? XColor.FromArgb(255, 28, 28, 28) : XColors.White);
        var state = graphics.Save();
        graphics.TranslateTransform(rect.X + size * .45 - bounds.Left, rect.Y + (rect.Height - bounds.Height) / 2 - bounds.Top);
        graphics.DrawPath(textBrush, PdfFinishing.ToXPath(outline));
        graphics.Restore(state);
    }

    private static void DrawNumber(XGraphics graphics, GlyphOutlines glyphs, Annotation mark, XPoint center, double width, double height, XColor color)
    {
        var diameter = AnnotationPainter.NumberSizeOf(mark.Width, (float)width, (float)height);
        var rect = new XRect(center.X - diameter / 2, center.Y - diameter / 2, diameter, diameter);
        var white = new XSolidBrush(XColors.White);
        graphics.DrawEllipse(white, rect);
        rect.Inflate(-diameter * .07, -diameter * .07);
        var fill = new XSolidBrush(color);
        graphics.DrawEllipse(fill, rect);
        var text = mark.Number.ToString(System.Globalization.CultureInfo.InvariantCulture);
        var size = (float)(diameter * (text.Length > 1 ? .42 : .5));
        using var outline = PdfFinishing.Outline(glyphs, text, size, bold: true);
        if (outline.IsEmpty)
            return;
        var bounds = outline.TightBounds;
        var ink = new XSolidBrush(XColors.White);
        var state = graphics.Save();
        graphics.TranslateTransform(rect.X + (rect.Width - bounds.Width) / 2 - bounds.Left,
            rect.Y + (rect.Height - bounds.Height) / 2 - bounds.Top);
        graphics.DrawPath(ink, PdfFinishing.ToXPath(outline));
        graphics.Restore(state);
    }

    private static XRect Rect(XPoint a, XPoint b) => new(Math.Min(a.X, b.X), Math.Min(a.Y, b.Y), Math.Abs(b.X - a.X), Math.Abs(b.Y - a.Y));
}

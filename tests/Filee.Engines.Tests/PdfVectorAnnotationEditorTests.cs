using Filee.Core.Presets;
using Filee.Engines.Pdf;
using PdfSharp.Drawing;
using PdfSharp.Pdf;
using PdfSharp.Pdf.IO;
using SkiaSharp;

namespace Filee.Engines.Tests;

public sealed class PdfVectorAnnotationEditorTests(EngineFixture fixture) : IClassFixture<EngineFixture>
{
    [Fact]
    public void Apply_appends_text_and_marks_as_vector_without_changing_source()
    {
        var folder = fixture.NewFolder();
        var source = Path.Combine(folder, "source.pdf");
        using (var document = new PdfDocument())
        {
            var page = document.AddPage();
            page.Width = XUnit.FromPoint(612);
            page.Height = XUnit.FromPoint(792);
            document.Save(source);
        }

        var output = Path.Combine(folder, "edited.pdf");
        File.Copy(source, output);
        var sourceBytes = File.ReadAllBytes(source);
        var marks = new[]
        {
            new PdfPageMark(0, new Annotation
            {
                Kind = AnnotationKind.Text,
                Color = "#4527A0",
                Text = "Vector edit",
                Points = [new AnnotationPoint { X = .12, Y = .18 }],
            }),
            new PdfPageMark(0, new Annotation
            {
                Kind = AnnotationKind.Highlight,
                Color = "#FFEB3B",
                Points = [new AnnotationPoint { X = .1, Y = .3 }, new AnnotationPoint { X = .8, Y = .36 }],
            }),
            new PdfPageMark(0, new Annotation
            {
                Kind = AnnotationKind.Pen,
                Color = "#D81B60",
                Points = [new AnnotationPoint { X = .2, Y = .5 }, new AnnotationPoint { X = .4, Y = .52 }, new AnnotationPoint { X = .55, Y = .49 }],
            }),
        };

        PdfVectorAnnotationEditor.Apply(output, marks, TestContext.Current.CancellationToken);

        Assert.Equal(sourceBytes, File.ReadAllBytes(source));
        using var result = PdfReader.Open(output, PdfDocumentOpenMode.Import);
        Assert.Single(result.Pages.Cast<PdfPage>());
        var preview = PdfPageTools.Preview(output, 0, 1000);
        using var bitmap = SKBitmap.Decode(preview.Png);
        Assert.NotNull(bitmap);
        var colored = 0;
        for (var y = 0; y < bitmap.Height; y++)
            for (var x = 0; x < bitmap.Width; x++)
            {
                var pixel = bitmap.GetPixel(x, y);
                if (pixel.Red < 245 || pixel.Green < 245 || pixel.Blue < 245)
                    colored++;
            }
        Assert.True(colored > 1000, $"Expected visible vector edits, found {colored} non-white pixels.");
        Assert.DoesNotContain("/Subtype /Image", System.Text.Encoding.Latin1.GetString(File.ReadAllBytes(output)));
    }

    [Fact]
    public void Apply_rejects_nonexistent_page_without_replacing_input()
    {
        var folder = fixture.NewFolder();
        var path = Path.Combine(folder, "source.pdf");
        using (var document = new PdfDocument())
        {
            document.AddPage();
            document.Save(path);
        }
        var before = File.ReadAllBytes(path);
        var mark = new Annotation { Kind = AnnotationKind.Highlight, Points = [new(), new() { X = .5, Y = .5 }] };

        Assert.Throws<ArgumentOutOfRangeException>(() => PdfVectorAnnotationEditor.Apply(path, [new(4, mark)], TestContext.Current.CancellationToken));
        Assert.Equal(before, File.ReadAllBytes(path));
    }
}

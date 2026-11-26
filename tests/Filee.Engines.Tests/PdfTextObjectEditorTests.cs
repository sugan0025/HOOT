using System.Text;
using Filee.Engines.Pdf;

namespace Filee.Engines.Tests;

public sealed class PdfTextObjectEditorTests(EngineFixture fixture) : IClassFixture<EngineFixture>
{
    [Fact]
    public void Replaces_existing_text_object_in_a_new_pdf_and_keeps_source_unchanged()
    {
        var folder = fixture.NewFolder();
        var source = Path.Combine(folder, "source.pdf");
        var output = Path.Combine(folder, "edited.pdf");
        WriteSimplePdf(source, "Original title");

        // Exercise the bundled PDFtoImage/PDFium renderer before and after editing. The text editor must not leave
        // PDFium's process-wide library in a state that breaks the existing preview path.
        var cancellationToken = TestContext.Current.CancellationToken;
        var originalPreview = PdfPreviewDocument.Open(source).RenderPage(1, 128, cancellationToken);
        Assert.NotEmpty(originalPreview.Png);

        var sourceBytes = File.ReadAllBytes(source);
        var text = PdfTextObjectEditor.ReadTextObjects(source, 0);
        var target = Assert.Single(text);
        Assert.Equal("Original title", target.Text);
        Assert.True(target.Right > target.Left);
        Assert.True(target.Top > target.Bottom);

        PdfTextObjectEditor.ReplaceTextObject(source, output, target.PageIndex, target.ObjectIndex, "Revised title", cancellationToken);

        Assert.Equal(sourceBytes, File.ReadAllBytes(source));
        var revised = Assert.Single(PdfTextObjectEditor.ReadTextObjects(output, 0));
        Assert.Equal("Revised title", revised.Text);
        var editedPreview = PdfPreviewDocument.Open(output).RenderPage(1, 128, cancellationToken);
        Assert.NotEmpty(editedPreview.Png);
    }

    [Fact]
    public void Deletes_and_adds_vector_text_objects_and_reports_page_dimensions()
    {
        var folder = fixture.NewFolder();
        var source = Path.Combine(folder, "source.pdf");
        var deleted = Path.Combine(folder, "deleted.pdf");
        var added = Path.Combine(folder, "added.pdf");
        WriteSimplePdf(source, "Remove me");

        var info = PdfTextObjectEditor.ReadPageInfo(source, 0);
        Assert.Equal(612, info.Width, 1);
        Assert.Equal(792, info.Height, 1);
        var target = Assert.Single(info.Objects);

        var ct = TestContext.Current.CancellationToken;
        PdfTextObjectEditor.DeleteTextObject(source, deleted, target.PageIndex, target.ObjectIndex, ct);
        Assert.Empty(PdfTextObjectEditor.ReadTextObjects(deleted, 0));

        PdfTextObjectEditor.AddTextObject(deleted, added, 0, "Added heading", 100, 650, fontSize: 16, r: 20, g: 40, b: 180, cancellationToken: ct);
        var addedObj = Assert.Single(PdfTextObjectEditor.ReadTextObjects(added, 0));
        Assert.Equal("Added heading", addedObj.Text);
        Assert.InRange(addedObj.Left, 95, 110);
        Assert.InRange(addedObj.Bottom, 645, 655);
    }

    [Fact]
    public void Replaces_multiple_selected_text_objects_as_one_phrase()
    {
        var folder = fixture.NewFolder();
        var source = Path.Combine(folder, "multi.pdf");
        var output = Path.Combine(folder, "multi_edited.pdf");
        var content = "BT /F1 14 Tf 72 700 Td (01.) Tj ET\nBT /F1 14 Tf 110 700 Td (Baki) Tj ET\n";
        WriteRawContentPdf(source, content);

        var items = PdfTextObjectEditor.ReadTextObjects(source, 0);
        Assert.Equal(2, items.Count);

        var ct = TestContext.Current.CancellationToken;
        PdfTextObjectEditor.ReplaceTextObjects(source, output, 0, [items[0].ObjectIndex, items[1].ObjectIndex], "01. Attack on Titan", ct);

        var revised = Assert.Single(PdfTextObjectEditor.ReadTextObjects(output, 0));
        Assert.Equal("01. Attack on Titan", revised.Text);
        Assert.InRange(revised.Left, 68, 76);
    }

    private static void WriteSimplePdf(string path, string text) =>
        WriteRawContentPdf(path, $"BT /F1 18 Tf 72 700 Td ({text}) Tj ET\n");

    private static void WriteRawContentPdf(string path, string content)
    {
        var objects = new[]
        {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            $"<< /Length {Encoding.ASCII.GetByteCount(content)} >>\nstream\n{content}endstream",
        };
        using var output = new MemoryStream();
        using var writer = new StreamWriter(output, Encoding.ASCII, leaveOpen: true);
        writer.Write("%PDF-1.4\n");
        writer.Flush();
        var offsets = new List<long> { 0 };
        foreach (var pdfObject in objects)
        {
            var objectNumber = offsets.Count;
            offsets.Add(output.Position);
            writer.Write($"{objectNumber} 0 obj\n{pdfObject}\nendobj\n");
            writer.Flush();
        }
        var xref = output.Position;
        writer.Write($"xref\n0 {offsets.Count}\n0000000000 65535 f \n");
        foreach (var offset in offsets.Skip(1))
            writer.Write($"{offset:D10} 00000 n \n");
        writer.Write($"trailer\n<< /Size {offsets.Count} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        writer.Flush();
        File.WriteAllBytes(path, output.ToArray());
    }
}

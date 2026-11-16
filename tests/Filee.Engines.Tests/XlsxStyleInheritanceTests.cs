using System.IO.Compression;
using System.Text;
using Filee.Engines.Office.Sheets;

namespace Filee.Engines.Tests;

public class XlsxStyleInheritanceTests(EngineFixture fx) : IClassFixture<EngineFixture>
{
    [Fact]
    public void Xlsx_named_cell_styles_inherit_base_formatting_and_allow_direct_overrides()
    {
        var xlsx = new XlsxBuilder();
        xlsx.Sheet("Styles", XlsxBuilder.Row(1,
            xlsx.Text("A1", "Inherited", 1),
            xlsx.Text("B1", "Override", 2),
            XlsxBuilder.Number("C1", 1234.5, 3)));
        var path = xlsx.Save(Path.Combine(fx.NewFolder(), "named-styles.xlsx"));
        ReplaceStyles(path, """
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts>
              <fonts count="3">
                <font><sz val="11"/></font>
                <font><b/><sz val="16"/><color rgb="FFFFFFFF"/></font>
                <font><i/><sz val="9"/><color rgb="FFFF0000"/></font>
              </fonts>
              <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F4E79"/></patternFill></fill></fills>
              <borders count="2"><border><left/><right/><top/><bottom/></border><border><left/><right/><top/><bottom style="thin"><color rgb="FF000000"/></bottom></border></borders>
              <cellStyleXfs count="2">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
                <xf numFmtId="164" fontId="1" fillId="1" borderId="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
              </cellStyleXfs>
              <cellXfs count="4">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf xfId="1"/>
                <xf xfId="1" fontId="2" applyFont="1"/>
                <xf xfId="1"/>
              </cellXfs>
              <cellStyles count="2"><cellStyle name="Normal" xfId="0"/><cellStyle name="Accent" xfId="1"/></cellStyles>
            </styleSheet>
            """);

        var sheet = Assert.Single(XlsxReader.Read(path).Sheets);
        var inherited = sheet.Get(0, 0)!;
        Assert.Equal((true, "#FFFFFF", "#1F4E79", CellAlign.Center, CellVerticalAlign.Center, true, "thin"),
            (inherited.Style.Bold, inherited.Style.Color, inherited.Style.Fill, inherited.Style.Align, inherited.Style.VerticalAlign, inherited.Style.Wrap, inherited.Style.Bottom.Style));
        Assert.Equal("1,234.50", sheet.Get(0, 2)!.Text);

        var overridden = sheet.Get(0, 1)!.Style;
        Assert.True(overridden.Italic);
        Assert.False(overridden.Bold);
        Assert.Equal("#FF0000", overridden.Color);
        Assert.Equal("#1F4E79", overridden.Fill);
        Assert.Equal(CellAlign.Center, overridden.Align);
        Assert.Equal("thin", overridden.Bottom.Style);
    }

    private static void ReplaceStyles(string path, string xml)
    {
        using var archive = ZipFile.Open(path, ZipArchiveMode.Update);
        archive.GetEntry("xl/styles.xml")!.Delete();
        using var writer = new StreamWriter(archive.CreateEntry("xl/styles.xml").Open(), new UTF8Encoding(false));
        writer.Write(xml);
    }
}

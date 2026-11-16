// DOCX tables: grid column widths, merged cells (gridSpan / vMerge), conditional table styles, borders and shading,
// cell margins, paragraph/run formatting, vertical alignment, row heights and repeated header rows.

using System.Xml.Linq;

namespace Filee.Engines.Hwp.Hwpx.Docx;

internal sealed partial class DocxReader
{
    private HTable ReadTable(XElement tbl)
    {
        var tblPr = tbl.Element(W + "tblPr");
        var style = _styles.GetValueOrDefault(Val(tblPr?.Element(W + "tblStyle")) ?? _defaultTableStyle ?? "");
        var styleChain = StyleChain(style);

        var grid = tbl.Element(W + "tblGrid")?.Elements(W + "gridCol").Select(c => HwpxUnits.FromTwips(Twips(c, "w") ?? 0)).ToArray() ?? [];
        var table = new HTable
        {
            ColumnWidths = grid.Length > 0 && grid.All(w => w > 0) ? grid : null,
            ColumnCount = grid.Length,
            Align = Val(tblPr?.Element(W + "jc")) switch
            {
                "center" => HAlign.Center,
                "right" or "end" => HAlign.Right,
                _ => HAlign.Left,
            },
            Indent = HwpxUnits.FromTwips(Twips(tblPr?.Element(W + "tblInd"), "w") ?? 0),
        };

        // Borders and cell margins: table style chain, then the table's own properties.
        var borders = new Dictionary<string, HBorder>();
        HInsets? margins = null;
        foreach (var properties in styleChain.Select(s => s.TblPr).Append(tblPr))
        {
            ApplyBorders(properties?.Element(W + "tblBorders"), borders);
            if (properties?.Element(W + "tblCellMar") is { } cellMar)
                margins = CellMargins(cellMar, margins);
        }
        table.CellMargin = margins;
        table.Borders = HBorders.Empty;

        var rows = tbl.Elements().SelectMany(e => e.Name == W + "tr" ? [e] : FlattenRows(e)).ToList();
        var columnCount = grid.Length > 0 ? grid.Length : rows
            .Select(row => (Twips(row.Element(W + "trPr")?.Element(W + "gridBefore"), "val") ?? 0)
                + row.Elements().SelectMany(e => e.Name == W + "tc" ? [e] : FlattenCells(e))
                    .Sum(cell => Math.Max(1, Twips(cell.Element(W + "tcPr")?.Element(W + "gridSpan"), "val") ?? 1)))
            .DefaultIfEmpty(0).Max();
        var conditions = TableConditions(tblPr, styleChain, rows.Count, columnCount);
        // vMerge: the cell that restarts a vertical merge, per grid column.
        var mergeOrigin = new Dictionary<int, HCell>();
        for (var r = 0; r < rows.Count; r++)
        {
            var tr = rows[r];
            var trPr = tr.Element(W + "trPr");
            var row = new HRow { Header = trPr?.Element(W + "tblHeader") is { } header && Val(header) is not ("0" or "false") };
            if (trPr?.Element(W + "trHeight") is { } height && Twips(height, "val") is > 0 and var h)
                row.Height = HwpxUnits.FromTwips(h);

            var column = Twips(trPr?.Element(W + "gridBefore"), "val") ?? 0;
            foreach (var tc in tr.Elements().SelectMany(e => e.Name == W + "tc" ? [e] : FlattenCells(e)))
            {
                var tcPr = tc.Element(W + "tcPr");
                var span = Math.Max(1, Twips(tcPr?.Element(W + "gridSpan"), "val") ?? 1);
                var vMerge = tcPr?.Element(W + "vMerge");
                if (vMerge is not null && Val(vMerge) is not "restart" && mergeOrigin.TryGetValue(column, out var origin))
                {
                    origin.RowSpan++;
                    var continuationBorders = new Dictionary<string, HBorder>
                    {
                        ["bottom"] = borders.GetValueOrDefault(r == rows.Count - 1 ? "bottom" : "insideH", HBorder.None),
                    };
                    foreach (var properties in styleChain.Select(s => s.TcPr)
                                 .Concat(conditions(r, column, span).Select(c => c.Element(W + "tcPr")))
                                 .Append(tcPr))
                        ApplyBorders(properties?.Element(W + "tcBorders"), continuationBorders);
                    if (origin.Borders is { } originBorders)
                        origin.Borders = originBorders with { Bottom = continuationBorders["bottom"] };
                    column += span;
                    continue;
                }

                var cell = new HCell { Column = column, ColSpan = span };
                var cellConditions = conditions(r, column, span);
                var oldParagraphLayers = _tableStyleParagraphLayers;
                var oldRunLayers = _tableStyleRunLayers;
                _tableStyleParagraphLayers = [.. styleChain.Select(s => s.PPr)
                    .Concat(cellConditions.Select(c => c.Element(W + "pPr"))).OfType<XElement>()];
                _tableStyleRunLayers = [.. styleChain.Select(s => s.RPr)
                    .Concat(cellConditions.Select(c => c.Element(W + "rPr"))).OfType<XElement>()];
                try
                {
                    ReadBlocks(tc.Elements(), cell.Blocks);
                }
                finally
                {
                    _tableStyleParagraphLayers = oldParagraphLayers;
                    _tableStyleRunLayers = oldRunLayers;
                }
                cell.VerticalAlign = Val(tcPr?.Element(W + "vAlign")) switch
                {
                    "center" => HVerticalAlign.Center,
                    "bottom" => HVerticalAlign.Bottom,
                    _ => HVerticalAlign.Top,
                };

                // Cell edges: outer table borders on the outside, inside borders between cells, cell overrides.
                var isFirstRow = r == 0;
                var isLastRow = r == rows.Count - 1;
                var cellBorders = new Dictionary<string, HBorder>
                {
                    ["left"] = borders.GetValueOrDefault(column == 0 ? "left" : "insideV", HBorder.None),
                    ["right"] = borders.GetValueOrDefault(column + span >= grid.Length ? "right" : "insideV", HBorder.None),
                    ["top"] = borders.GetValueOrDefault(isFirstRow ? "top" : "insideH", HBorder.None),
                    ["bottom"] = borders.GetValueOrDefault(isLastRow ? "bottom" : "insideH", HBorder.None),
                };
                string? fill = null;
                var cellProperties = styleChain.Select(s => s.TcPr)
                    .Concat(cellConditions.Select(c => c.Element(W + "tcPr")))
                    .Append(tcPr);
                foreach (var properties in cellProperties)
                {
                    // Sides a cell leaves out keep the padding its neighbours get: the table's, else the HWPX default.
                    if (properties?.Element(W + "tcMar") is { } cellMar)
                        cell.CellMargin = CellMargins(cellMar, cell.CellMargin ?? table.CellMargin ?? new HInsets(510, 510, 141, 141));
                    ApplyBorders(properties?.Element(W + "tcBorders"), cellBorders);
                    if (properties?.Element(W + "shd") is { } shd)
                        fill = ShadingColor(shd) ?? fill;
                }
                cell.Borders = new HBorders(cellBorders["left"], cellBorders["right"], cellBorders["top"], cellBorders["bottom"]);
                cell.Fill = fill;

                if (vMerge is not null)
                    mergeOrigin[column] = cell;
                else
                    mergeOrigin.Remove(column);
                row.Cells.Add(cell);
                column += span;
            }
            table.Rows.Add(row);
            table.ColumnCount = Math.Max(table.ColumnCount, column);
        }
        return table;
    }

    private static Func<int, int, int, List<XElement>> TableConditions(
        XElement? tblPr, List<Style> styleChain, int rowCount, int columnCount)
    {
        var look = tblPr?.Element(W + "tblLook");
        var mask = int.TryParse(Val(look), System.Globalization.NumberStyles.HexNumber, null, out var value) ? value : 0;
        bool IsOn(string name, int bit) => Attr(look, name) is { } enabled
            ? enabled is "1" or "true"
            : (mask & bit) != 0;
        var firstRow = IsOn("firstRow", 0x20);
        var lastRow = IsOn("lastRow", 0x40);
        var firstColumn = IsOn("firstColumn", 0x80);
        var lastColumn = IsOn("lastColumn", 0x100);
        var rowBands = look is not null && !IsOn("noHBand", 0x200);
        var columnBands = look is not null && !IsOn("noVBand", 0x400);
        int BandSize(string name) => styleChain.Select(s => s.TblPr).Append(tblPr)
            .Select(p => Twips(p?.Element(W + name), "val")).LastOrDefault(size => size is > 0) ?? 1;
        var rowBandSize = BandSize("tblStyleRowBandSize");
        var columnBandSize = BandSize("tblStyleColBandSize");
        var byType = styleChain.SelectMany(s => s.TableConditions).ToLookup(c => Attr(c, "type") ?? "");
        if (byType.Count == 0)
            return (_, _, _) => [];

        return (row, column, span) =>
        {
            var isFirstRow = firstRow && row == 0;
            var isLastRow = lastRow && row == rowCount - 1;
            var isFirstColumn = firstColumn && column == 0;
            var isLastColumn = lastColumn && column + span >= columnCount;
            var types = new List<string>();
            if (rowBands && !isFirstRow && !isLastRow)
                types.Add((row - (firstRow ? 1 : 0)) / rowBandSize % 2 == 0 ? "band1Horz" : "band2Horz");
            if (columnBands && !isFirstColumn && !isLastColumn)
                types.Add((column - (firstColumn ? 1 : 0)) / columnBandSize % 2 == 0 ? "band1Vert" : "band2Vert");
            if (isLastColumn) types.Add("lastCol");
            if (isFirstColumn) types.Add("firstCol");
            if (isLastRow) types.Add("lastRow");
            if (isFirstRow) types.Add("firstRow");
            if (isFirstRow && isFirstColumn) types.Add("nwCell");
            if (isFirstRow && isLastColumn) types.Add("neCell");
            if (isLastRow && isFirstColumn) types.Add("swCell");
            if (isLastRow && isLastColumn) types.Add("seCell");
            return [.. types.SelectMany(type => byType[type])];
        };
    }

    private List<Style> StyleChain(Style? style)
    {
        var chain = new List<Style>();
        for (var current = style; current is not null && chain.Count < 20; current = current.BasedOn is { } b ? _styles.GetValueOrDefault(b) : null)
            chain.Insert(0, current);
        return chain;
    }

    private static IEnumerable<XElement> FlattenRows(XElement element) =>
        element.Name == W + "sdt" ? element.Element(W + "sdtContent")?.Elements().SelectMany(e => e.Name == W + "tr" ? [e] : FlattenRows(e)) ?? []
        : element.Name == W + "customXml" ? element.Elements().SelectMany(e => e.Name == W + "tr" ? [e] : FlattenRows(e))
        : [];

    private static IEnumerable<XElement> FlattenCells(XElement element) =>
        element.Name == W + "sdt" ? element.Element(W + "sdtContent")?.Elements().SelectMany(e => e.Name == W + "tc" ? [e] : FlattenCells(e)) ?? []
        : element.Name == W + "customXml" ? element.Elements().SelectMany(e => e.Name == W + "tc" ? [e] : FlattenCells(e))
        : [];

    private static void ApplyBorders(XElement? borders, Dictionary<string, HBorder> target)
    {
        foreach (var edge in borders?.Elements() ?? [])
        {
            var name = edge.Name.LocalName switch
            {
                "start" => "left",
                "end" => "right",
                var other => other,
            };
            target[name] = Border(edge);
        }
    }

    /// <summary>A Word border (w:val style, w:sz in eighths of a point, w:color).</summary>
    private static HBorder Border(XElement edge)
    {
        var style = Val(edge) switch
        {
            null or "nil" or "none" => HBorderStyle.None,
            "dotted" => HBorderStyle.Dot,
            "dashed" or "dashSmallGap" => HBorderStyle.Dash,
            "dotDash" or "dotDotDash" => HBorderStyle.DashDot,
            "double" or "triple" or "thinThickSmallGap" or "thickThinSmallGap" => HBorderStyle.Double,
            _ => HBorderStyle.Solid,
        };
        var eighths = Twips(edge, "sz") ?? 4;
        var color = Attr(edge, "color") is { Length: 6 } hex ? "#" + hex.ToUpperInvariant() : "#000000";
        return new HBorder(style, eighths / 8.0 * 25.4 / 72, color);
    }

    private static HInsets CellMargins(XElement cellMar, HInsets? current)
    {
        int Get(string name, string alternative, int fallback)
        {
            var side = cellMar.Element(W + name) ?? cellMar.Element(W + alternative);
            if (Attr(side, "type") == "nil")
                return 0;
            return Twips(side, "w") is { } twips ? HwpxUnits.FromTwips(twips) : fallback;
        }
        var existing = current ?? new HInsets(540, 540, 0, 0);
        return new HInsets(Get("left", "start", existing.Left), Get("right", "end", existing.Right), Get("top", "top", existing.Top), Get("bottom", "bottom", existing.Bottom));
    }

}

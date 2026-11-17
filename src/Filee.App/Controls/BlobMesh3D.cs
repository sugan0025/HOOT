using System.Collections.Generic;
using System.Linq;
using System.Numerics;
using Avalonia;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Rendering.SceneGraph;
using Avalonia.Skia;
using Filee.App.Controls;
using SkiaSharp;

namespace Filee.App.Controls;

/// <summary>
/// A closed, smoothly shaded 2.5D shell built from the same animated Bezier contour as the 2D Blob.
/// The mesh is a companion-only 3D surface built from the shared Blobatar physics contour.
/// </summary>
internal sealed class BlobMesh : ICustomDrawOperation
{
    // 40 samples per cubic keep the projected Bezier silhouette smooth at companion scale.
    // 25 latitude rings retain soft shading while cutting per-frame mesh allocations by over half.
    private const int Segments = 160, Rings = 25;
    private static readonly ushort[] Indices = BuildIndices();
    private static readonly MeshEdge[] Edges = BuildEdges();
    private readonly SKPoint[] _points = new SKPoint[VertexCount];
    private readonly SKColor[] _colors = new SKColor[VertexCount];
    private readonly ushort[] _visible = new ushort[Indices.Length];
    private readonly bool[] _visibleFaces = new bool[TriangleCount];
    private readonly int[] _firstNeighbor = new int[VertexCount];
    private readonly int[] _secondNeighbor = new int[VertexCount];
    private int _visibleCount;
    private int _boundaryEdgeCount;
    private int _boundaryStart = -1;
    private readonly bool _wireframe;

    public Rect Bounds { get; } = new(0, 0, 110, 110);
    internal Rect ProjectedSurfaceBounds { get; }
    public static int VertexCount => Rings * Segments + 2;
    public static int TriangleCount => Indices.Length / 3;

    internal static Matrix4x4 SurfaceRotation(BlobatarState state) => Matrix4x4.CreateRotationY((float)state.MeshSpin);

    public BlobMesh(BlobatarState state)
    {
        _wireframe = state.MeshWireframe;
        var frame = state.Frame;
        // Cursor gaze belongs to the eyes. Rotating the entire shell with eye gaze makes the silhouette
        // appear to deform as the pointer moves; only the explicit turn animation rotates the 3D body.
        var rotation = SurfaceRotation(state);
        var positions = BuildVertices(frame);
        var transformed = new Vector3[positions.Length];
        Array.Fill(_firstNeighbor, -1);
        Array.Fill(_secondNeighbor, -1);
        var transformedNormals = BuildSmoothNormals(positions);
        var light = Vector3.Normalize(new Vector3(-.35f, -.65f, 1));
        var specularDirection = Vector3.Normalize(light + Vector3.UnitZ);
        var look = state.Look;
        var minX = float.PositiveInfinity;
        var minY = float.PositiveInfinity;
        var maxX = float.NegativeInfinity;
        var maxY = float.NegativeInfinity;

        for (var i = 0; i < positions.Length; i++)
        {
            var p = Vector3.Transform(positions[i], rotation);
            var n = Vector3.Normalize(Vector3.TransformNormal(transformedNormals[i], rotation));
            transformed[i] = p;
            _points[i] = new SKPoint(p.X + (float)BlobatarLayout.FaceX, p.Y + (float)BlobatarLayout.BodyCy);
            minX = Math.Min(minX, _points[i].X);
            minY = Math.Min(minY, _points[i].Y);
            maxX = Math.Max(maxX, _points[i].X);
            maxY = Math.Max(maxY, _points[i].Y);

            var gradient = Math.Clamp((p.Y + 33) / 66, 0, 1);
            var a = gradient < .48f ? look.Hi : look.Mid;
            var b = gradient < .48f ? look.Mid : look.Lo;
            var t = gradient < .48f ? gradient / .48f : (gradient - .48f) / .52f;
            var diffuse = .87f + .16f * Math.Max(0, Vector3.Dot(n, light));
            var specular = MathF.Pow(Math.Max(0, Vector3.Dot(n, specularDirection)), 36) * 14;
            byte Channel(byte x, byte y) => (byte)Math.Clamp((x + (y - x) * t) * diffuse + specular, 0, 255);
            _colors[i] = new SKColor(Channel(a.R, b.R), Channel(a.G, b.G), Channel(a.B, b.B));
        }
        ProjectedSurfaceBounds = new Rect(minX, minY, maxX - minX, maxY - minY);

        // The shell is convex. Front-facing triangles partition its visible projection, so a
        // depth sort is unnecessary; avoiding it prevents a per-frame O(n log n) animation cost.
        for (var i = 0; i < Indices.Length; i += 3)
        {
            var a = Indices[i]; var b = Indices[i + 1]; var c = Indices[i + 2];
            var face = i / 3;
            var isVisible = Vector3.Cross(transformed[b] - transformed[a], transformed[c] - transformed[a]).Z > 0;
            _visibleFaces[face] = isVisible;
            if (isVisible)
            {
                _visible[_visibleCount++] = a;
                _visible[_visibleCount++] = b;
                _visible[_visibleCount++] = c;
            }
        }

        foreach (var edge in Edges)
        {
            var frontA = _visibleFaces[edge.FaceA];
            var frontB = edge.FaceB >= 0 && _visibleFaces[edge.FaceB];
            if (frontA == frontB) continue;
            AddBoundaryNeighbor(edge.A, edge.B);
            AddBoundaryNeighbor(edge.B, edge.A);
            _boundaryEdgeCount++;
            if (_boundaryStart < 0) _boundaryStart = edge.A;
        }
    }

    private void AddBoundaryNeighbor(int vertex, int neighbor)
    {
        if (_firstNeighbor[vertex] < 0) _firstNeighbor[vertex] = neighbor;
        else if (_secondNeighbor[vertex] < 0) _secondNeighbor[vertex] = neighbor;
    }

    private static Vector3[] BuildVertices(BlobatarFrame frame)
    {
        var result = new Vector3[VertexCount];
        var controls = BlobatarRenderer.BodyRestPoints;
        Vector2 Control(int index)
        {
            var point = controls[index];
            if (frame.Contour is { } contour && index < contour.Length)
                point += contour[index];
            if (index == 7) point.Y += frame.TuftL * .4f;
            if (index == 11) point.Y += frame.TuftR * .4f;
            return point;
        }

        result[0] = new Vector3(0, 0, -26);
        for (var segment = 0; segment < Segments; segment++)
        {
            var curve = segment / (Segments / 4);
            var t = (segment % (Segments / 4)) / (float)(Segments / 4);
            var u = 1 - t;
            var first = curve * 3;
            var p = u * u * u * Control(first)
                + 3 * u * u * t * Control((first + 1) % 12)
                + 3 * u * t * t * Control((first + 2) % 12)
                + t * t * t * Control((first + 3) % 12);

            for (var ring = 0; ring < Rings; ring++)
            {
                var latitude = -MathF.PI / 2 + (ring + 1) * MathF.PI / (Rings + 1);
                var radius = MathF.Cos(latitude);
                var index = 1 + ring * Segments + segment;
                result[index] = new Vector3(
                    (p.X - (float)BlobatarLayout.FaceX) * radius,
                    (p.Y - (float)BlobatarLayout.BodyCy) * radius,
                    26 * MathF.Sin(latitude));
            }
        }
        result[^1] = new Vector3(0, 0, 26);
        return result;
    }

    private static Vector3[] BuildSmoothNormals(Vector3[] positions)
    {
        var normals = new Vector3[positions.Length];
        normals[0] = -Vector3.UnitZ;
        normals[^1] = Vector3.UnitZ;
        for (var ring = 0; ring < Rings; ring++)
        {
            var previousRing = ring == 0 ? 0 : ring - 1;
            var nextRing = ring == Rings - 1 ? Rings - 1 : ring + 1;
            for (var segment = 0; segment < Segments; segment++)
            {
                var previousSegment = (segment + Segments - 1) % Segments;
                var nextSegment = (segment + 1) % Segments;
                var index = 1 + ring * Segments + segment;
                var around = positions[1 + ring * Segments + nextSegment] - positions[1 + ring * Segments + previousSegment];
                var across = positions[1 + nextRing * Segments + segment] - positions[1 + previousRing * Segments + segment];
                var normal = Vector3.Cross(around, across);
                if (normal.LengthSquared() < 1e-8f) normal = Vector3.UnitZ;
                if (Vector3.Dot(normal, positions[index]) < 0) normal = -normal;
                normals[index] = Vector3.Normalize(normal);
            }
        }
        return normals;
    }

    private static ushort[] BuildIndices()
    {
        var indices = new List<ushort>(Segments * Rings * 6);
        void Triangle(int a, int b, int c)
        {
            var normal = Vector3.Cross(RestVertex(b) - RestVertex(a), RestVertex(c) - RestVertex(a));
            if (Vector3.Dot(normal, RestVertex(a) + RestVertex(b) + RestVertex(c)) < 0) (b, c) = (c, b);
            indices.Add((ushort)a); indices.Add((ushort)b); indices.Add((ushort)c);
        }

        Vector3 RestVertex(int index)
        {
            if (index == 0) return new Vector3(0, 0, -26);
            if (index == VertexCount - 1) return new Vector3(0, 0, 26);
            var ring = (index - 1) / Segments;
            var segment = (index - 1) % Segments;
            var latitude = -MathF.PI / 2 + (ring + 1) * MathF.PI / (Rings + 1);
            var t = segment / (float)Segments * MathF.Tau;
            return new Vector3(35 * MathF.Cos(t) * MathF.Cos(latitude), 33 * MathF.Sin(t) * MathF.Cos(latitude), 26 * MathF.Sin(latitude));
        }

        for (var segment = 0; segment < Segments; segment++)
        {
            var next = (segment + 1) % Segments;
            Triangle(0, 1 + next, 1 + segment);
            for (var ring = 0; ring < Rings - 1; ring++)
            {
                var a = 1 + ring * Segments + segment;
                var b = 1 + ring * Segments + next;
                Triangle(a, b, a + Segments);
                Triangle(b, b + Segments, a + Segments);
            }
            Triangle(VertexCount - 1, 1 + (Rings - 1) * Segments + segment, 1 + (Rings - 1) * Segments + next);
        }
        return indices.ToArray();
    }

    private readonly record struct MeshEdge(ushort A, ushort B, int FaceA, int FaceB);

    private static MeshEdge[] BuildEdges()
    {
        var adjacency = new Dictionary<(ushort A, ushort B), (int FirstFace, int SecondFace)>();
        for (var face = 0; face < TriangleCount; face++)
        {
            var offset = face * 3;
            Add(Indices[offset], Indices[offset + 1], face);
            Add(Indices[offset + 1], Indices[offset + 2], face);
            Add(Indices[offset + 2], Indices[offset], face);
        }

        void Add(ushort first, ushort second, int face)
        {
            var key = first < second ? (first, second) : (second, first);
            if (adjacency.TryGetValue(key, out var pair)) adjacency[key] = (pair.FirstFace, face);
            else adjacency.Add(key, (face, -1));
        }

        return adjacency.Select(pair => new MeshEdge(pair.Key.A, pair.Key.B, pair.Value.FirstFace, pair.Value.SecondFace)).ToArray();
    }

    private SKPath BuildSilhouettePath()
    {
        var path = new SKPath();
        if (_boundaryStart < 0 || _boundaryEdgeCount < 3) return path;
        var start = _boundaryStart;
        var previous = -1;
        var current = start;
        var closed = false;
        var contour = new List<SKPoint>(_boundaryEdgeCount);
        contour.Add(_points[current]);
        for (var step = 0; step <= _boundaryEdgeCount; step++)
        {
            var first = _firstNeighbor[current];
            var second = _secondNeighbor[current];
            var next = first >= 0 && first != previous ? first : second;
            if (next < 0) break;
            if (next == start)
            {
                closed = true;
                break;
            }
            contour.Add(_points[next]);
            previous = current;
            current = next;
        }
        if (!closed || contour.Count < 3) return path;

        // Round the sampled triangle boundary with quadratic arcs through each mesh sample.
        // This preserves the projected silhouette while removing the tiny polygon facets at turns.
        static SKPoint Midpoint(SKPoint a, SKPoint b) => new((a.X + b.X) * .5f, (a.Y + b.Y) * .5f);
        var last = contour[^1];
        var firstPoint = contour[0];
        path.MoveTo(Midpoint(last, firstPoint));
        for (var i = 0; i < contour.Count; i++)
            path.QuadTo(contour[i], Midpoint(contour[i], contour[(i + 1) % contour.Count]));
        path.Close();
        return path;
    }

    public void Render(ImmediateDrawingContext context)
    {
        var feature = context.TryGetFeature<ISkiaSharpApiLeaseFeature>();
        if (feature is null || _visibleCount == 0) return;
        using var lease = feature.Lease();
        using var paint = new SKPaint { IsAntialias = true, Color = SKColors.White };
        using var vertices = SKVertices.CreateCopy(SKVertexMode.Triangles, _points, null, _colors, _visible.AsSpan(0, _visibleCount).ToArray());
        using var silhouette = BuildSilhouettePath();
        var canvas = lease.SkCanvas;
        var saveCount = canvas.Save();
        try
        {
            if (!silhouette.IsEmpty)
                canvas.ClipPath(silhouette, SKClipOperation.Intersect, antialias: true);
            canvas.DrawVertices(vertices, SKBlendMode.Modulate, paint);
            if (_wireframe)
            {
                paint.Color = new SKColor(40, 30, 90, 115); paint.StrokeWidth = .16f; paint.Style = SKPaintStyle.Stroke;
                using var path = new SKPath();
                for (var i = 0; i < _visibleCount; i += 3)
                {
                    path.MoveTo(_points[_visible[i]]); path.LineTo(_points[_visible[i + 1]]);
                    path.LineTo(_points[_visible[i + 2]]); path.Close();
                }
                canvas.DrawPath(path, paint);
            }
        }
        finally { canvas.RestoreToCount(saveCount); }
    }

    public bool HitTest(Point point) => Bounds.Contains(point);
    public bool Equals(ICustomDrawOperation? other) => ReferenceEquals(this, other);
    public void Dispose() { }
}

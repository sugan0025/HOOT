// Edits existing PDF text objects through the PDFium version already bundled by PDFtoImage.
// Calls share PDFtoImage's process-wide lock because PDFium itself is not thread-safe.

using System.Runtime.InteropServices;

namespace Filee.Engines.Pdf;

public sealed record PdfTextObject(int PageIndex, int ObjectIndex, string Text, double Left, double Bottom,
    double Right, double Top, double FontSize);

public sealed record PdfPageTextInfo(double Width, double Height, IReadOnlyList<PdfTextObject> Objects);

/// <summary>Reads, replaces, adds, and deletes existing PDF text objects without rasterizing pages.</summary>
public static class PdfTextObjectEditor
{
    // PDFtoImage 5.2.1 uses this interned monitor for every PDFium call (NativeMethods.cs). Keep the package pinned
    // and re-check this contract when upgrading PDFtoImage.
    private static readonly string PdfiumLock = string.Intern("e362349b-001d-4cb2-bf55-a71606a3e36f");
    private static readonly WriteBlockDelegate WriteBlock = WritePdfBlock;
    private static bool _pdfiumInitializedByRenderer;

    private const int PdfiumTextObject = 1;
    private const int SaveNoIncremental = 0;

    public static IReadOnlyList<PdfTextObject> ReadTextObjects(string path, int pageIndex) =>
        ReadPageInfo(path, pageIndex).Objects;

    public static PdfPageTextInfo ReadPageInfo(string path, int pageIndex)
    {
        lock (PdfiumLock)
        {
            using var session = PdfiumSession.Open(path);
            var pageCount = Native.FPDF_GetPageCount(session.Document);
            if (pageIndex < 0 || pageIndex >= pageCount)
                throw new ArgumentOutOfRangeException(nameof(pageIndex));

            var page = Native.FPDF_LoadPage(session.Document, pageIndex);
            if (page == IntPtr.Zero)
                throw new InvalidDataException("PDFium could not open this PDF page.");
            try
            {
                var width = (double)Native.FPDF_GetPageWidthF(page);
                var height = (double)Native.FPDF_GetPageHeightF(page);
                var textPage = Native.FPDFText_LoadPage(page);
                if (textPage == IntPtr.Zero)
                    throw new InvalidDataException("PDFium could not read text from this PDF page.");
                try
                {
                    var result = new List<PdfTextObject>();
                    var count = Native.FPDFPage_CountObjects(page);
                    for (var objectIndex = 0; objectIndex < count; objectIndex++)
                    {
                        var obj = Native.FPDFPage_GetObject(page, objectIndex);
                        if (obj == IntPtr.Zero || Native.FPDFPageObj_GetType(obj) != PdfiumTextObject)
                            continue;
                        var text = ReadObjectText(obj, textPage);
                        if (string.IsNullOrWhiteSpace(text))
                            continue;
                        if (Native.FPDFPageObj_GetBounds(obj, out var left, out var bottom, out var right, out var top) == 0)
                            continue;
                        Native.FPDFTextObj_GetFontSize(obj, out var fontSize);
                        result.Add(new PdfTextObject(pageIndex, objectIndex, text, left, bottom, right, top, fontSize));
                    }
                    return new PdfPageTextInfo(width, height, result);
                }
                finally { Native.FPDFText_ClosePage(textPage); }
            }
            finally { Native.FPDF_ClosePage(page); }
        }
    }

    /// <summary>Replaces one existing text object (falling back to a standard vector font if the embedded font is subsetted) and writes the result to a separate output file.</summary>
    public static void ReplaceTextObject(string sourcePath, string outputPath, int pageIndex, int objectIndex,
        string replacement, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(outputPath);
        ArgumentNullException.ThrowIfNull(replacement);
        if (replacement.Contains('\0'))
            throw new ArgumentException("PDF text cannot contain a NUL character.", nameof(replacement));
        if (string.IsNullOrWhiteSpace(replacement))
        {
            DeleteTextObject(sourcePath, outputPath, pageIndex, objectIndex, cancellationToken);
            return;
        }

        lock (PdfiumLock)
        {
            cancellationToken.ThrowIfCancellationRequested();
            using var session = PdfiumSession.Open(sourcePath);
            var count = Native.FPDF_GetPageCount(session.Document);
            if (pageIndex < 0 || pageIndex >= count)
                throw new ArgumentOutOfRangeException(nameof(pageIndex));
            var page = Native.FPDF_LoadPage(session.Document, pageIndex);
            if (page == IntPtr.Zero)
                throw new InvalidDataException("PDFium could not open this PDF page.");
            try
            {
                var objectCount = Native.FPDFPage_CountObjects(page);
                if (objectIndex < 0 || objectIndex >= objectCount)
                    throw new ArgumentOutOfRangeException(nameof(objectIndex));
                var obj = Native.FPDFPage_GetObject(page, objectIndex);
                if (obj == IntPtr.Zero || Native.FPDFPageObj_GetType(obj) != PdfiumTextObject)
                    throw new InvalidOperationException("The selected PDF object is no longer editable text.");
                var value = Marshal.StringToHGlobalUni(replacement);
                try
                {
                    if (Native.FPDFText_SetText(obj, value) == 0)
                    {
                        // Subsetted / CID-encoded fonts often reject new glyphs in FPDFText_SetText.
                        // Fall back to replacing the object with a standard vector Helvetica text object at the same position, size, and color.
                        if (Native.FPDFPageObj_GetBounds(obj, out var left, out var bottom, out _, out _) == 0)
                            throw new InvalidOperationException("PDFium could not read the bounds of this text object.");
                        if (Native.FPDFTextObj_GetFontSize(obj, out var fontSize) == 0 || fontSize <= 0)
                            fontSize = 12f;
                        if (Native.FPDFPageObj_GetFillColor(obj, out var r, out var g, out var b, out var a) == 0)
                            (r, g, b, a) = (0, 0, 0, 255);

                        var replacementObj = Native.FPDFPageObj_NewTextObj(session.Document, "Helvetica", fontSize);
                        if (replacementObj == IntPtr.Zero)
                            throw new InvalidOperationException("PDFium could not create a replacement vector text object.");
                        try
                        {
                            if (Native.FPDFText_SetText(replacementObj, value) == 0)
                                throw new InvalidOperationException("PDFium could not encode the replacement text.");
                            Native.FPDFPageObj_SetFillColor(replacementObj, r, g, b, a);
                            Native.FPDFPageObj_Transform(replacementObj, 1, 0, 0, 1, left, bottom);
                        }
                        catch
                        {
                            Native.FPDFPageObj_Destroy(replacementObj);
                            throw;
                        }

                        if (Native.FPDFPage_RemoveObject(page, obj) != 0)
                            Native.FPDFPageObj_Destroy(obj);
                        Native.FPDFPage_InsertObject(page, replacementObj);
                    }
                }
                finally { Marshal.FreeHGlobal(value); }
                if (Native.FPDFPage_GenerateContent(page) == 0)
                    throw new InvalidOperationException("PDFium could not regenerate the page content after replacing the text.");
            }
            finally { Native.FPDF_ClosePage(page); }

            cancellationToken.ThrowIfCancellationRequested();
            SaveCopy(session.Document, outputPath);
        }
    }

    /// <summary>Replaces one or more existing text objects on a page with a single replacement string.</summary>
    public static void ReplaceTextObjects(string sourcePath, string outputPath, int pageIndex,
        IReadOnlyList<int> objectIndices, string replacement, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(objectIndices);
        if (objectIndices.Count == 0)
            throw new ArgumentException("At least one text object must be selected.", nameof(objectIndices));
        if (objectIndices.Count == 1)
        {
            ReplaceTextObject(sourcePath, outputPath, pageIndex, objectIndices[0], replacement, cancellationToken);
            return;
        }
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(outputPath);
        ArgumentNullException.ThrowIfNull(replacement);
        if (replacement.Contains('\0'))
            throw new ArgumentException("PDF text cannot contain a NUL character.", nameof(replacement));
        if (string.IsNullOrWhiteSpace(replacement))
        {
            DeleteTextObjects(sourcePath, outputPath, pageIndex, objectIndices, cancellationToken);
            return;
        }

        lock (PdfiumLock)
        {
            cancellationToken.ThrowIfCancellationRequested();
            using var session = PdfiumSession.Open(sourcePath);
            var count = Native.FPDF_GetPageCount(session.Document);
            if (pageIndex < 0 || pageIndex >= count)
                throw new ArgumentOutOfRangeException(nameof(pageIndex));
            var page = Native.FPDF_LoadPage(session.Document, pageIndex);
            if (page == IntPtr.Zero)
                throw new InvalidDataException("PDFium could not open this PDF page.");
            try
            {
                var objectCount = Native.FPDFPage_CountObjects(page);
                var distinct = objectIndices.Distinct().ToList();
                var anchorLeft = float.MaxValue;
                var anchorBottom = 0f;
                var anchorFontSize = 12f;
                uint anchorR = 0, anchorG = 0, anchorB = 0, anchorA = 255;

                foreach (var idx in distinct)
                {
                    if (idx < 0 || idx >= objectCount)
                        throw new ArgumentOutOfRangeException(nameof(objectIndices));
                    var obj = Native.FPDFPage_GetObject(page, idx);
                    if (obj == IntPtr.Zero || Native.FPDFPageObj_GetType(obj) != PdfiumTextObject)
                        throw new InvalidOperationException("One of the selected PDF objects is no longer editable text.");
                    if (Native.FPDFPageObj_GetBounds(obj, out var left, out var bottom, out _, out _) != 0 && left < anchorLeft)
                    {
                        anchorLeft = left;
                        anchorBottom = bottom;
                        if (Native.FPDFTextObj_GetFontSize(obj, out var fs) != 0 && fs > 0)
                            anchorFontSize = fs;
                        if (Native.FPDFPageObj_GetFillColor(obj, out var r, out var g, out var b, out var a) != 0)
                            (anchorR, anchorG, anchorB, anchorA) = (r, g, b, a);
                    }
                }
                if (anchorLeft == float.MaxValue)
                    anchorLeft = 72f;

                var value = Marshal.StringToHGlobalUni(replacement);
                IntPtr replacementObj;
                try
                {
                    replacementObj = Native.FPDFPageObj_NewTextObj(session.Document, "Helvetica", anchorFontSize);
                    if (replacementObj == IntPtr.Zero)
                        throw new InvalidOperationException("PDFium could not create a replacement vector text object.");
                    try
                    {
                        if (Native.FPDFText_SetText(replacementObj, value) == 0)
                            throw new InvalidOperationException("PDFium could not encode the replacement text.");
                        Native.FPDFPageObj_SetFillColor(replacementObj, anchorR, anchorG, anchorB, anchorA);
                        Native.FPDFPageObj_Transform(replacementObj, 1, 0, 0, 1, anchorLeft, anchorBottom);
                    }
                    catch
                    {
                        Native.FPDFPageObj_Destroy(replacementObj);
                        throw;
                    }
                }
                finally { Marshal.FreeHGlobal(value); }

                foreach (var idx in distinct.OrderByDescending(i => i))
                {
                    var obj = Native.FPDFPage_GetObject(page, idx);
                    if (obj != IntPtr.Zero && Native.FPDFPage_RemoveObject(page, obj) != 0)
                        Native.FPDFPageObj_Destroy(obj);
                }
                Native.FPDFPage_InsertObject(page, replacementObj);
                if (Native.FPDFPage_GenerateContent(page) == 0)
                    throw new InvalidOperationException("PDFium could not regenerate the page content after replacing the text.");
            }
            finally { Native.FPDF_ClosePage(page); }

            cancellationToken.ThrowIfCancellationRequested();
            SaveCopy(session.Document, outputPath);
        }
    }

    /// <summary>Deletes one or more existing text objects from the page.</summary>
    public static void DeleteTextObjects(string sourcePath, string outputPath, int pageIndex,
        IReadOnlyList<int> objectIndices, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(objectIndices);
        if (objectIndices.Count == 0)
            throw new ArgumentException("At least one text object must be selected.", nameof(objectIndices));
        if (objectIndices.Count == 1)
        {
            DeleteTextObject(sourcePath, outputPath, pageIndex, objectIndices[0], cancellationToken);
            return;
        }
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(outputPath);

        lock (PdfiumLock)
        {
            cancellationToken.ThrowIfCancellationRequested();
            using var session = PdfiumSession.Open(sourcePath);
            var count = Native.FPDF_GetPageCount(session.Document);
            if (pageIndex < 0 || pageIndex >= count)
                throw new ArgumentOutOfRangeException(nameof(pageIndex));
            var page = Native.FPDF_LoadPage(session.Document, pageIndex);
            if (page == IntPtr.Zero)
                throw new InvalidDataException("PDFium could not open this PDF page.");
            try
            {
                var objectCount = Native.FPDFPage_CountObjects(page);
                foreach (var idx in objectIndices.Distinct().OrderByDescending(i => i))
                {
                    if (idx < 0 || idx >= objectCount)
                        throw new ArgumentOutOfRangeException(nameof(objectIndices));
                    var obj = Native.FPDFPage_GetObject(page, idx);
                    if (obj != IntPtr.Zero && Native.FPDFPageObj_GetType(obj) == PdfiumTextObject
                        && Native.FPDFPage_RemoveObject(page, obj) != 0)
                        Native.FPDFPageObj_Destroy(obj);
                }
                if (Native.FPDFPage_GenerateContent(page) == 0)
                    throw new InvalidOperationException("PDFium could not regenerate the page content after deleting the text.");
            }
            finally { Native.FPDF_ClosePage(page); }

            cancellationToken.ThrowIfCancellationRequested();
            SaveCopy(session.Document, outputPath);
        }
    }

    /// <summary>Deletes one existing text object from the page and writes the result to a separate output file.</summary>
    public static void DeleteTextObject(string sourcePath, string outputPath, int pageIndex, int objectIndex,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(outputPath);

        lock (PdfiumLock)
        {
            cancellationToken.ThrowIfCancellationRequested();
            using var session = PdfiumSession.Open(sourcePath);
            var count = Native.FPDF_GetPageCount(session.Document);
            if (pageIndex < 0 || pageIndex >= count)
                throw new ArgumentOutOfRangeException(nameof(pageIndex));
            var page = Native.FPDF_LoadPage(session.Document, pageIndex);
            if (page == IntPtr.Zero)
                throw new InvalidDataException("PDFium could not open this PDF page.");
            try
            {
                var objectCount = Native.FPDFPage_CountObjects(page);
                if (objectIndex < 0 || objectIndex >= objectCount)
                    throw new ArgumentOutOfRangeException(nameof(objectIndex));
                var obj = Native.FPDFPage_GetObject(page, objectIndex);
                if (obj == IntPtr.Zero || Native.FPDFPageObj_GetType(obj) != PdfiumTextObject)
                    throw new InvalidOperationException("The selected PDF object is no longer editable text.");
                if (Native.FPDFPage_RemoveObject(page, obj) == 0)
                    throw new InvalidOperationException("PDFium could not remove the selected text object.");
                Native.FPDFPageObj_Destroy(obj);
                if (Native.FPDFPage_GenerateContent(page) == 0)
                    throw new InvalidOperationException("PDFium could not regenerate the page content after deleting the text.");
            }
            finally { Native.FPDF_ClosePage(page); }

            cancellationToken.ThrowIfCancellationRequested();
            SaveCopy(session.Document, outputPath);
        }
    }

    /// <summary>Adds a new vector text object at the specified PDF page coordinates (points from bottom-left).</summary>
    public static void AddTextObject(string sourcePath, string outputPath, int pageIndex, string text,
        double x, double y, double fontSize = 14, byte r = 0, byte g = 0, byte b = 0,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(outputPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(text);
        if (text.Contains('\0'))
            throw new ArgumentException("PDF text cannot contain a NUL character.", nameof(text));
        if (fontSize <= 0)
            fontSize = 14;

        lock (PdfiumLock)
        {
            cancellationToken.ThrowIfCancellationRequested();
            using var session = PdfiumSession.Open(sourcePath);
            var count = Native.FPDF_GetPageCount(session.Document);
            if (pageIndex < 0 || pageIndex >= count)
                throw new ArgumentOutOfRangeException(nameof(pageIndex));
            var page = Native.FPDF_LoadPage(session.Document, pageIndex);
            if (page == IntPtr.Zero)
                throw new InvalidDataException("PDFium could not open this PDF page.");
            try
            {
                var newObj = Native.FPDFPageObj_NewTextObj(session.Document, "Helvetica", (float)fontSize);
                if (newObj == IntPtr.Zero)
                    throw new InvalidOperationException("PDFium could not create a new vector text object.");
                var value = Marshal.StringToHGlobalUni(text);
                try
                {
                    if (Native.FPDFText_SetText(newObj, value) == 0)
                        throw new InvalidOperationException("PDFium could not encode the text.");
                    Native.FPDFPageObj_SetFillColor(newObj, r, g, b, 255);
                    Native.FPDFPageObj_Transform(newObj, 1, 0, 0, 1, x, y);
                }
                catch
                {
                    Native.FPDFPageObj_Destroy(newObj);
                    throw;
                }
                finally { Marshal.FreeHGlobal(value); }

                Native.FPDFPage_InsertObject(page, newObj);
                if (Native.FPDFPage_GenerateContent(page) == 0)
                    throw new InvalidOperationException("PDFium could not regenerate the page content after adding text.");
            }
            finally { Native.FPDF_ClosePage(page); }

            cancellationToken.ThrowIfCancellationRequested();
            SaveCopy(session.Document, outputPath);
        }
    }

    private static string ReadObjectText(IntPtr obj, IntPtr textPage)
    {
        var nativeLength = Native.FPDFTextObj_GetText(obj, textPage, IntPtr.Zero, UIntPtr.Zero);
        var length = nativeLength.ToUInt64();
        if (length == 0 || length > 1_000_000)
            return "";
        var buffer = Marshal.AllocHGlobal(checked((int)length));
        try
        {
            if (Native.FPDFTextObj_GetText(obj, textPage, buffer, new UIntPtr((uint)length)).ToUInt64() == 0)
                return "";
            return Marshal.PtrToStringUni(buffer)?.TrimEnd('\0') ?? "";
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }

    private static void SaveCopy(IntPtr document, string outputPath)
    {
        var fullPath = Path.GetFullPath(outputPath);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
        var temporaryPath = fullPath + ".hoot-writing";
        using var stream = new MemoryStream();
        var context = GCHandle.Alloc(stream);
        try
        {
            var writer = new NativeFileWrite { Version = 1, WriteBlock = Marshal.GetFunctionPointerForDelegate(WriteBlock) };
            writer.UserData = GCHandle.ToIntPtr(context);
            if (Native.FPDF_SaveAsCopy(document, ref writer, SaveNoIncremental) == 0)
                throw new IOException("PDFium could not write the edited PDF.");
            File.WriteAllBytes(temporaryPath, stream.ToArray());
            File.Move(temporaryPath, fullPath, overwrite: true);
        }
        finally
        {
            if (context.IsAllocated)
                context.Free();
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    private static int WritePdfBlock(IntPtr writerPointer, IntPtr data, UIntPtr nativeSize)
    {
        var size = nativeSize.ToUInt64();
        if (size > int.MaxValue)
            return 0;
        try
        {
            var writer = Marshal.PtrToStructure<NativeFileWrite>(writerPointer);
            var stream = (MemoryStream?)GCHandle.FromIntPtr(writer.UserData).Target;
            if (stream is null)
                return 0;
            var buffer = new byte[(int)size];
            Marshal.Copy(data, buffer, 0, buffer.Length);
            stream.Write(buffer);
            return 1;
        }
        catch { return 0; }
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int WriteBlockDelegate(IntPtr thisPointer, IntPtr data, UIntPtr size);

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeFileWrite
    {
        public int Version;
        public IntPtr WriteBlock;
        // PDFium passes a pointer to this structure as the callback's first parameter.
        // UserData is an extra tail field that PDFium does not read.
        public IntPtr UserData;
    }

    private sealed class PdfiumSession : IDisposable
    {
        private readonly GCHandle _bytes;
        public IntPtr Document { get; }

        private PdfiumSession(byte[] bytes)
        {
            _bytes = GCHandle.Alloc(bytes, GCHandleType.Pinned);
            Document = Native.FPDF_LoadMemDocument64(_bytes.AddrOfPinnedObject(), new UIntPtr((ulong)bytes.LongLength), IntPtr.Zero);
            if (Document == IntPtr.Zero)
            {
                _bytes.Free();
                throw new InvalidDataException("PDFium could not open this PDF. It may be damaged, encrypted, or use unsupported permissions.");
            }
        }

        public static PdfiumSession Open(string path)
        {
            EnsurePdfiumInitialized(path);
            return new PdfiumSession(File.ReadAllBytes(path));
        }

        public void Dispose()
        {
            Native.FPDF_CloseDocument(Document);
            _bytes.Free();
        }
    }

    /// <summary>
    /// Lets PDFtoImage own PDFium's process-wide initialization and shutdown. Calling FPDF_DestroyLibrary from an
    /// individual text-edit operation would invalidate PDFtoImage's shared runtime and break later previews.
    /// The renderer initializes its singleton once; the interned lock is reentrant on this thread.
    /// </summary>
    private static void EnsurePdfiumInitialized(string path)
    {
        if (_pdfiumInitializedByRenderer)
            return;
        _ = PdfPreviewDocument.Open(path);
        _pdfiumInitializedByRenderer = true;
    }

    private static class Native
    {
        private const string Library = "pdfium";

        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)] internal static extern IntPtr FPDF_LoadMemDocument64(IntPtr data, UIntPtr size, IntPtr password);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern void FPDF_CloseDocument(IntPtr document);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDF_GetPageCount(IntPtr document);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern IntPtr FPDF_LoadPage(IntPtr document, int pageIndex);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern void FPDF_ClosePage(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern float FPDF_GetPageWidthF(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern float FPDF_GetPageHeightF(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPage_CountObjects(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern IntPtr FPDFPage_GetObject(IntPtr page, int index);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern void FPDFPage_InsertObject(IntPtr page, IntPtr pageObject);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPage_RemoveObject(IntPtr page, IntPtr pageObject);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern void FPDFPageObj_Destroy(IntPtr pageObject);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)] internal static extern IntPtr FPDFPageObj_NewTextObj(IntPtr document, [MarshalAs(UnmanagedType.LPStr)] string font, float fontSize);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPageObj_GetType(IntPtr obj);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPageObj_GetBounds(IntPtr obj, out float left, out float bottom, out float right, out float top);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPageObj_GetFillColor(IntPtr obj, out uint r, out uint g, out uint b, out uint a);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPageObj_SetFillColor(IntPtr obj, uint r, uint g, uint b, uint a);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern void FPDFPageObj_Transform(IntPtr obj, double a, double b, double c, double d, double e, double f);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFTextObj_GetFontSize(IntPtr obj, out float size);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern IntPtr FPDFText_LoadPage(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern void FPDFText_ClosePage(IntPtr textPage);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern UIntPtr FPDFTextObj_GetText(IntPtr obj, IntPtr textPage, IntPtr buffer, UIntPtr length);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFText_SetText(IntPtr obj, IntPtr text);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPage_GenerateContent(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDF_SaveAsCopy(IntPtr document, ref NativeFileWrite writer, int flags);
    }
}

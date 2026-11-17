// Edits existing PDF text objects through the PDFium version already bundled by PDFtoImage.
// Calls share PDFtoImage's process-wide lock because PDFium itself is not thread-safe.

using System.Runtime.InteropServices;

namespace Filee.Engines.Pdf;

public sealed record PdfTextObject(int PageIndex, int ObjectIndex, string Text, double Left, double Bottom,
    double Right, double Top, double FontSize);

/// <summary>Reads and replaces existing PDF text objects without rasterizing pages.</summary>
public static class PdfTextObjectEditor
{
    // PDFtoImage 5.2.1 uses this interned monitor for every PDFium call (NativeMethods.cs). Keep the package pinned
    // and re-check this contract when upgrading PDFtoImage.
    private static readonly string PdfiumLock = string.Intern("e362349b-001d-4cb2-bf55-a71606a3e36f");
    private static readonly WriteBlockDelegate WriteBlock = WritePdfBlock;
    private static bool _pdfiumInitializedByRenderer;

    private const int PdfiumTextObject = 1;
    private const int SaveNoIncremental = 0;

    public static IReadOnlyList<PdfTextObject> ReadTextObjects(string path, int pageIndex)
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
                    return result;
                }
                finally { Native.FPDFText_ClosePage(textPage); }
            }
            finally { Native.FPDF_ClosePage(page); }
        }
    }

    /// <summary>Replaces one existing text object and writes the result to a separate output file.</summary>
    public static void ReplaceTextObject(string sourcePath, string outputPath, int pageIndex, int objectIndex,
        string replacement, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(outputPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(replacement);
        if (replacement.Contains('\0'))
            throw new ArgumentException("PDF text cannot contain a NUL character.", nameof(replacement));

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
                        throw new InvalidOperationException("PDFium could not replace this text. The font may not support the replacement characters.");
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
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPage_CountObjects(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern IntPtr FPDFPage_GetObject(IntPtr page, int index);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPageObj_GetType(IntPtr obj);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPageObj_GetBounds(IntPtr obj, out float left, out float bottom, out float right, out float top);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFTextObj_GetFontSize(IntPtr obj, out float size);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern IntPtr FPDFText_LoadPage(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern void FPDFText_ClosePage(IntPtr textPage);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern UIntPtr FPDFTextObj_GetText(IntPtr obj, IntPtr textPage, IntPtr buffer, UIntPtr length);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFText_SetText(IntPtr obj, IntPtr text);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDFPage_GenerateContent(IntPtr page);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)] internal static extern int FPDF_SaveAsCopy(IntPtr document, ref NativeFileWrite writer, int flags);
    }
}

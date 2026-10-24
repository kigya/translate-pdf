"""Real PDFium regressions for source-preserving text replacement and navigation."""
import ctypes
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from pdf_export_test import ROOT, run, sample_pdf, mixed_type3_inline_pdf, pdfium_snapshot

FONT = ROOT / 'assets/fonts/NotoSans-Regular.ttf'


def toc_pdf(path, wrapped=False, label=None):
    """Make a source TOC row with page number and dot leader in source text."""
    def stream(data):
        return b'<< /Length ' + str(len(data)).encode() + b' >>\nstream\n' + data + b'\nendstream'

    def text(font, x, y, value):
        literal = value.encode('ascii').replace(b'\\', b'\\\\').replace(b'(', b'\\(').replace(b')', b'\\)')
        return (b'BT /' + font.encode('ascii') + b' 1 Tf 9 0 0 9 ' + str(x).encode() + b' ' + str(y).encode() +
                b' Tm (' + literal + b') Tj ET')

    if wrapped:
        commands = [text('F1', 70, 700, 'Reporting safety'),
                    text('F2', 70, 691, 'defects to owners'),
                    # The leader and reference are a separate source object,
                    # but share the continuation line and follow its label.
                    text('F2', 147, 691, '. . . . . . 387')]
    else:
        label = label or 'Reporting Safety Defects'
        commands = [text('F1', 70, 700, label + ' . . . . . . 387')]
    commands.append(b'1 0 0 RG 420 400 80 50 re S')
    content = b'\n'.join(commands)
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [0 0 612 792] '
        b'/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>',
        stream(content),
    ]
    data = b'%PDF-1.4\n'
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(data)
    data += f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += (f'trailer << /Size {len(objects) + 1} /Root 1 0 R >>\n'
             f'startxref\n{xref}\n%%EOF\n').encode()
    path.write_bytes(data)


def table_cells_pdf(path, values=None, navigation=False, kerned_header=False,
                    multiline_numeric=False, clipped_header=False):
    """Put multiple table-cell values in one PDF text object, divided by rules."""
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import DecodedStreamObject, NameObject

    values = values or ['Fan', '15', 'Rear']
    if len(values) not in (2, 3):
        raise ValueError('the source-table fixture supports two or three columns')
    if navigation:
        navigation_pdf(path)

    columns = [(30, 300, 570), (30, 210, 400, 570)][len(values) == 3]
    starts = [columns[0] + 20]
    starts += [columns[index] + 40 for index in range(1, len(values))]
    widths = {'F': 611, 'a': 556, 'n': 556, 'R': 667, 'e': 556, 'r': 333,
              'w': 722, 'd': 556, ' ': 278, '.': 278, 'L': 611, '2': 556,
              '0': 556, '1': 556, '5': 556}
    command = []
    if kerned_header:
        command.append('BT /F1 9 Tf 1 0 0 1 55 735 Tm [(Fluid) -1200 (Usage)] TJ ET')
    if clipped_header:
        command.append('BT /F1 9 Tf 4 Tr 1 0 0 1 55 735 Tm (Clipped) Tj ET')
    command.append(f'BT /F1 12 Tf 0 Tr 1 0 0 1 {starts[0]} 680 Tm [')
    for index, value in enumerate(values):
        if any(char not in widths for char in value):
            raise ValueError('table fixture glyph is missing a Helvetica width')
        command.append(f'({value})')
        if index + 1 < len(values):
            advance = sum(widths[char] for char in value) / 1000 * 12
            adjustment = round((advance - (starts[index + 1] - starts[index])) * 1000 / 12)
            # TJ applies -adjustment/1000 em after the preceding string.
            command.append(f' {adjustment} ')
    command.append('] TJ ET')
    if multiline_numeric:
        command.append(f'BT /F1 12 Tf 1 0 0 1 {starts[1]} 665 Tm (L) Tj ET')
    command.append('0 G 0.6 w 30 650 m 570 650 l S 30 710 m 570 710 l S')
    for x in columns[1:-1]:
        command.append(f'{x} 650 m {x} 710 l S')
    raw = ('\n'.join(command)).encode('ascii')
    if navigation:
        reader = PdfReader(path)
        writer = PdfWriter(reader)
        content = DecodedStreamObject()
        content.set_data(raw)
        writer.pages[0][NameObject('/Contents')] = writer._add_object(content)
        with path.open('wb') as output:
            writer.write(output)
        return

    stream = b'<< /Length ' + str(len(raw)).encode() + b' >>\nstream\n' + raw + b'\nendstream'
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [30 40 582 752] '
        b'/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
        stream,
    ]
    data = b'%PDF-1.4\n'
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(data)
    data += f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += (f'trailer << /Size {len(objects) + 1} /Root 1 0 R >>\n'
             f'startxref\n{xref}\n%%EOF\n').encode()
    path.write_bytes(data)


def pictogram_pdf(path, content, include_image=False):
    """Use a standard source Zapf Dingbats text object as a movable glyph fixture."""
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject, NumberObject
    sample_pdf(path)
    writer = PdfWriter(PdfReader(path))
    writer.pages[0]['/Resources']['/Font'][NameObject('/F2')] = writer._add_object(DictionaryObject({
        NameObject('/Type'): NameObject('/Font'),
        NameObject('/Subtype'): NameObject('/Type1'),
        NameObject('/BaseFont'): NameObject('/ZapfDingbats'),
    }))
    if include_image:
        image = DecodedStreamObject()
        image.set_data(b'\x20\x80\xe0')
        image.update({NameObject('/Type'): NameObject('/XObject'),
                      NameObject('/Subtype'): NameObject('/Image'),
                      NameObject('/Width'): NumberObject(1),
                      NameObject('/Height'): NumberObject(1),
                      NameObject('/ColorSpace'): NameObject('/DeviceRGB'),
                      NameObject('/BitsPerComponent'): NumberObject(8)})
        writer.pages[0]['/Resources'][NameObject('/XObject')] = DictionaryObject({
            NameObject('/Im1'): writer._add_object(image)})
        content += b' q 8 0 0 8 120 695 cm /Im1 Do Q'
    content += b' 1 0 0 RG 420 400 80 50 re S'
    stream = DecodedStreamObject()
    stream.set_data(content)
    writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
    with path.open('wb') as output:
        writer.write(output)


def mixed_text_style_pdf(path, content):
    """Build a compact source page with regular, bold, and italic Type1 fonts."""
    def stream(data):
        return b'<< /Length ' + str(len(data)).encode() + b' >>\nstream\n' + data + b'\nendstream'

    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [0 0 612 792] '
        b'/Resources << /Font << /F1 4 0 R /FB 5 0 R /FI 6 0 R >> >> /Contents 7 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique /Encoding /WinAnsiEncoding >>',
        stream(content),
    ]
    data = b'%PDF-1.4\n'
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(data)
    data += f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += (f'trailer << /Size {len(objects) + 1} /Root 1 0 R >>\n'
             f'startxref\n{xref}\n%%EOF\n').encode()
    path.write_bytes(data)


def illustration_image_pdf(path, rotation=0, complex_clip=False):
    """Create one crop-intersecting PDF image, optionally behind a complex clip."""
    from pypdf import PdfWriter
    from pypdf.generic import (DecodedStreamObject, DictionaryObject, NameObject,
                               NumberObject, RectangleObject)

    writer = PdfWriter()
    page = writer.add_blank_page(width=200, height=300)
    page.cropbox = RectangleObject([20, 30, 180, 270])
    if rotation:
        page.rotate(rotation)
    pixels = DecodedStreamObject()
    pixels.set_data(b'\x40\x90\xe0')
    pixels.update({NameObject('/Type'): NameObject('/XObject'),
                   NameObject('/Subtype'): NameObject('/Image'),
                   NameObject('/Width'): NumberObject(1),
                   NameObject('/Height'): NumberObject(1),
                   NameObject('/ColorSpace'): NameObject('/DeviceRGB'),
                   NameObject('/BitsPerComponent'): NumberObject(8)})
    page[NameObject('/Resources')] = DictionaryObject({
        NameObject('/XObject'): DictionaryObject({NameObject('/Illustration'): writer._add_object(pixels)})
    })
    if complex_clip:
        content = b'q 0 0 m 90 0 l 45 90 l h W n 140 0 0 140 10 60 cm /Illustration Do Q'
    else:
        content = b'q 140 0 0 140 10 60 cm /Illustration Do Q'
    stream = DecodedStreamObject()
    stream.set_data(content)
    page[NameObject('/Contents')] = writer._add_object(stream)
    with path.open('wb') as output:
        writer.write(output)


def calibrated_image_mask_pdf(path):
    """A CalRGB-filled stencil beside text that forces PDFium to regenerate content."""
    from pypdf import PdfWriter
    from pypdf.generic import (ArrayObject, BooleanObject, DecodedStreamObject, DictionaryObject,
                               FloatObject, NameObject, NumberObject)

    writer=PdfWriter()
    page=writer.add_blank_page(width=200,height=300)
    font=writer._add_object(DictionaryObject({NameObject('/Type'):NameObject('/Font'),
      NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')}))
    stencil=DecodedStreamObject()
    stencil.set_data(b'\x00')
    stencil.update({NameObject('/Type'):NameObject('/XObject'),NameObject('/Subtype'):NameObject('/Image'),
      NameObject('/Width'):NumberObject(1),NameObject('/Height'):NumberObject(1),
      NameObject('/ImageMask'):BooleanObject(True),
      NameObject('/BitsPerComponent'):NumberObject(1)})
    calrgb=ArrayObject([NameObject('/CalRGB'),DictionaryObject({
      NameObject('/WhitePoint'):ArrayObject([FloatObject(.9505),FloatObject(1),FloatObject(1.089)]),
      NameObject('/Gamma'):ArrayObject([FloatObject(2.2)]*3)})])
    page[NameObject('/Resources')]=DictionaryObject({
      NameObject('/Font'):DictionaryObject({NameObject('/F1'):font}),
      NameObject('/XObject'):DictionaryObject({NameObject('/Stencil'):writer._add_object(stencil)}),
      NameObject('/ColorSpace'):DictionaryObject({NameObject('/CS0'):calrgb})})
    content=DecodedStreamObject()
    content.set_data(b'BT /F1 14 Tf 20 220 Td (Service operation.) Tj ET '
      b'q /CS0 cs .137 .121 .125 sc 100 0 0 100 20 60 cm /Stencil Do Q')
    page[NameObject('/Contents')]=writer._add_object(content)
    with path.open('wb') as output: writer.write(output)


def image_integrity_pdf(path,variant):
    """Two-page source with one image type whose preview data can be tampered independently."""
    from pypdf import PdfWriter
    from pypdf.generic import (ArrayObject, ByteStringObject, DecodedStreamObject,
                               DictionaryObject, NameObject, NumberObject)

    writer=PdfWriter()
    page=writer.add_blank_page(width=200,height=300)
    image=DecodedStreamObject()
    image.set_data(b'\x10\x50\x90' if variant!='indexed' else b'\x00')
    image_dict={NameObject('/Type'):NameObject('/XObject'),NameObject('/Subtype'):NameObject('/Image'),
      NameObject('/Width'):NumberObject(1),NameObject('/Height'):NumberObject(1),NameObject('/BitsPerComponent'):NumberObject(8)}
    if variant=='rgb':
      image_dict[NameObject('/ColorSpace')]=NameObject('/DeviceRGB')
    elif variant=='indexed':
      image_dict[NameObject('/ColorSpace')]=ArrayObject([NameObject('/Indexed'),NameObject('/DeviceRGB'),
        NumberObject(1),ByteStringObject(b'\x10\x50\x90\xe0\x20\x30')])
    elif variant=='smask':
      image_dict[NameObject('/ColorSpace')]=NameObject('/DeviceRGB')
      alpha=DecodedStreamObject();alpha.set_data(b'\xff')
      alpha.update({NameObject('/Type'):NameObject('/XObject'),NameObject('/Subtype'):NameObject('/Image'),
        NameObject('/Width'):NumberObject(1),NameObject('/Height'):NumberObject(1),
        NameObject('/ColorSpace'):NameObject('/DeviceGray'),NameObject('/BitsPerComponent'):NumberObject(8)})
      image_dict[NameObject('/SMask')]=writer._add_object(alpha)
    else:
      raise ValueError('unsupported synthetic image variant')
    image.update(image_dict)
    font=writer._add_object(DictionaryObject({NameObject('/Type'):NameObject('/Font'),
      NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')}))
    page[NameObject('/Resources')]=DictionaryObject({
      NameObject('/Font'):DictionaryObject({NameObject('/F1'):font}),
      NameObject('/XObject'):DictionaryObject({NameObject('/Artwork'):writer._add_object(image)})})
    content=DecodedStreamObject()
    content.set_data(b'BT /F1 14 Tf 20 220 Td (Service operation.) Tj ET q 100 0 0 100 20 60 cm /Artwork Do Q')
    page[NameObject('/Contents')]=writer._add_object(content)
    writer.add_blank_page(width=200,height=300)
    writer.add_outline_item('Second page',1)
    writer.add_named_destination('second-page',1)
    with path.open('wb') as output: writer.write(output)


def mutate_preview_image(preview, variant):
    """Change one preview image's visual data without touching page geometry."""
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import ByteStringObject, DecodedStreamObject, NameObject

    writer = PdfWriter(PdfReader(preview))
    page = writer.pages[0]
    resources = page['/Resources'].get_object()
    xobjects = resources['/XObject'].get_object()
    images = [(name, reference.get_object()) for name, reference in xobjects.items()
              if reference.get_object().get('/Subtype') == '/Image']
    if not images:
        raise AssertionError('tamper fixture must contain a preview image')
    image_name, image = images[0]
    if variant == 'rgb':
        decoded = bytearray(image.get_data())
        decoded[0] ^= 0x7f
        stream = DecodedStreamObject()
        stream.set_data(bytes(decoded))
        for key, value in image.items():
            if key not in ('/Length', '/Filter', '/DecodeParms'):
                stream[NameObject(key)] = value
        xobjects[image_name] = writer._add_object(stream)
    elif variant == 'indexed':
        color_space = image['/ColorSpace'].get_object()
        # Keep every image index byte unchanged; alter only its lookup color.
        color_space[3] = ByteStringObject(b'\x7f\x50\x90\xe0\x20\x30')
    elif variant == 'smask':
        mask = image['/SMask'].get_object()
        decoded = bytearray(mask.get_data())
        decoded[0] ^= 0x7f
        stream = DecodedStreamObject()
        stream.set_data(bytes(decoded))
        for key, value in mask.items():
            if key not in ('/Length', '/Filter', '/DecodeParms'):
                stream[NameObject(key)] = value
        image[NameObject('/SMask')] = writer._add_object(stream)
    else:
        raise ValueError(f'unsupported image tamper variant: {variant}')
    with Path(preview).open('wb') as output:
        writer.write(output)


def pdfium_symbol_snapshot(path):
    """Inspect original symbol text, font, style and transform through PDFium."""
    lib = ctypes.CDLL(str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib/libpdfium.dylib'))
    pointer = ctypes.c_void_p
    signatures = {
        'FPDF_LoadDocument': ([ctypes.c_char_p, ctypes.c_char_p], pointer),
        'FPDF_LoadPage': ([pointer, ctypes.c_int], pointer),
        'FPDFText_LoadPage': ([pointer], pointer),
        'FPDFPage_CountObjects': ([pointer], ctypes.c_int),
        'FPDFPage_GetObject': ([pointer, ctypes.c_int], pointer),
        'FPDFPageObj_GetType': ([pointer], ctypes.c_int),
        'FPDFTextObj_GetText': ([pointer, pointer, pointer, ctypes.c_ulong], ctypes.c_ulong),
        'FPDFTextObj_GetFont': ([pointer], pointer),
        'FPDFTextObj_GetFontSize': ([pointer, ctypes.POINTER(ctypes.c_float)], ctypes.c_int),
        'FPDFPageObj_GetMatrix': ([pointer, ctypes.POINTER(PdfiumMatrix)], ctypes.c_int),
        'FPDFPageObj_GetFillColor': ([pointer] + [ctypes.POINTER(ctypes.c_uint)] * 4, ctypes.c_int),
        'FPDFPageObj_GetStrokeColor': ([pointer] + [ctypes.POINTER(ctypes.c_uint)] * 4, ctypes.c_int),
        'FPDFPageObj_GetStrokeWidth': ([pointer, ctypes.POINTER(ctypes.c_float)], ctypes.c_int),
        'FPDFTextObj_GetTextRenderMode': ([pointer], ctypes.c_int),
        'FPDFFont_GetBaseFontName': ([pointer, pointer, ctypes.c_size_t], ctypes.c_size_t),
        'FPDFText_ClosePage': ([pointer], None),
        'FPDF_ClosePage': ([pointer], None),
        'FPDF_CloseDocument': ([pointer], None),
        'FPDF_DestroyLibrary': ([], None),
    }
    for name, (arguments, result) in signatures.items():
        function = getattr(lib, name)
        function.argtypes = arguments
        function.restype = result
    lib.FPDF_InitLibrary()
    document = page = text_page = None
    try:
        document = lib.FPDF_LoadDocument(str(path).encode(), None)
        if not document:
            raise AssertionError('pictogram fixture must load in PDFium')
        page = lib.FPDF_LoadPage(document, 0)
        text_page = lib.FPDFText_LoadPage(page)
        result = []
        for index in range(lib.FPDFPage_CountObjects(page)):
            obj = lib.FPDFPage_GetObject(page, index)
            if lib.FPDFPageObj_GetType(obj) != 1:
                continue
            font = lib.FPDFTextObj_GetFont(obj)
            name_size = lib.FPDFFont_GetBaseFontName(font, None, 0)
            name = ctypes.create_string_buffer(name_size)
            lib.FPDFFont_GetBaseFontName(font, name, len(name))
            font_name = name.value.decode().lower()
            if 'dingbat' not in font_name:
                continue
            byte_size = lib.FPDFTextObj_GetText(obj, text_page, None, 0)
            text_buffer = (ctypes.c_ushort * (byte_size // 2))()
            if not byte_size or lib.FPDFTextObj_GetText(obj, text_page, text_buffer, byte_size) != byte_size:
                raise AssertionError('PDFium must return original pictogram text')
            font_size = ctypes.c_float()
            matrix = PdfiumMatrix()
            stroke_width = ctypes.c_float()
            colors = []
            for getter in (lib.FPDFPageObj_GetFillColor, lib.FPDFPageObj_GetStrokeColor):
                rgba = [ctypes.c_uint() for _ in range(4)]
                colors.append(tuple(value.value for value in rgba) if getter(obj, *(ctypes.byref(value) for value in rgba))
                              else None)
            if not lib.FPDFTextObj_GetFontSize(obj, ctypes.byref(font_size)) or not lib.FPDFPageObj_GetMatrix(obj, ctypes.byref(matrix)):
                raise AssertionError('PDFium must return pictogram size and transform')
            has_stroke_width = bool(lib.FPDFPageObj_GetStrokeWidth(obj, ctypes.byref(stroke_width)))
            result.append({
                'text': bytes(text_buffer).decode('utf-16le').rstrip('\0'), 'font': font_name,
                'font_size': font_size.value,
                'matrix': tuple(getattr(matrix, field) for field in ('a', 'b', 'c', 'd', 'e', 'f')),
                'colors': tuple(colors), 'render_mode': lib.FPDFTextObj_GetTextRenderMode(obj),
                'stroke_width': stroke_width.value if has_stroke_width else None,
            })
        return result
    finally:
        if text_page:
            lib.FPDFText_ClosePage(text_page)
        if page:
            lib.FPDF_ClosePage(page)
        if document:
            lib.FPDF_CloseDocument(document)
        lib.FPDF_DestroyLibrary()


class PdfiumMatrix(ctypes.Structure):
    _fields_ = [('a', ctypes.c_float), ('b', ctypes.c_float), ('c', ctypes.c_float),
                ('d', ctypes.c_float), ('e', ctypes.c_float), ('f', ctypes.c_float)]


def pdfium_character_snapshot(path, page_index=0):
    """Capture glyph identity, original font and placement from a PDFium page."""
    lib = ctypes.CDLL(str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib/libpdfium.dylib'))
    pointer = ctypes.c_void_p
    signatures = {
        'FPDF_LoadDocument': ([ctypes.c_char_p, ctypes.c_char_p], pointer),
        'FPDF_LoadPage': ([pointer, ctypes.c_int], pointer),
        'FPDFText_LoadPage': ([pointer], pointer),
        'FPDFText_CountChars': ([pointer], ctypes.c_int),
        'FPDFText_GetUnicode': ([pointer, ctypes.c_int], ctypes.c_uint),
        'FPDFText_GetTextObject': ([pointer, ctypes.c_int], pointer),
        'FPDFText_GetCharOrigin': ([pointer, ctypes.c_int, ctypes.POINTER(ctypes.c_double), ctypes.POINTER(ctypes.c_double)], ctypes.c_int),
        'FPDFText_GetCharBox': ([pointer, ctypes.c_int] + [ctypes.POINTER(ctypes.c_double)] * 4, ctypes.c_int),
        'FPDFTextObj_GetFont': ([pointer], pointer),
        'FPDFTextObj_GetFontSize': ([pointer, ctypes.POINTER(ctypes.c_float)], ctypes.c_int),
        'FPDFPageObj_GetMatrix': ([pointer, ctypes.POINTER(PdfiumMatrix)], ctypes.c_int),
        'FPDFFont_GetBaseFontName': ([pointer, pointer, ctypes.c_size_t], ctypes.c_size_t),
        'FPDFText_ClosePage': ([pointer], None),
        'FPDF_ClosePage': ([pointer], None),
        'FPDF_CloseDocument': ([pointer], None),
        'FPDF_DestroyLibrary': ([], None),
    }
    for name, (arguments, result) in signatures.items():
        function = getattr(lib, name)
        function.argtypes = arguments
        function.restype = result
    lib.FPDF_InitLibrary()
    document = page = text_page = None
    try:
        document = lib.FPDF_LoadDocument(str(path).encode(), None)
        if not document:
            raise AssertionError('table fixture must load in PDFium')
        page = lib.FPDF_LoadPage(document, page_index)
        text_page = lib.FPDFText_LoadPage(page)
        result = []
        for index in range(lib.FPDFText_CountChars(text_page)):
            codepoint = lib.FPDFText_GetUnicode(text_page, index)
            value = chr(codepoint)
            if value.isspace():
                result.append({'index': index, 'text': value})
                continue
            obj = lib.FPDFText_GetTextObject(text_page, index)
            if not obj:
                raise AssertionError(f'PDFium must map source glyph {index} U+{codepoint:04X} to its text object')
            font = lib.FPDFTextObj_GetFont(obj)
            if not font:
                raise AssertionError(f'PDFium must return the source font for glyph {index} U+{codepoint:04X}')
            name_size = lib.FPDFFont_GetBaseFontName(font, None, 0)
            name = ctypes.create_string_buffer(name_size)
            lib.FPDFFont_GetBaseFontName(font, name, len(name))
            size, matrix = ctypes.c_float(), PdfiumMatrix()
            x, y = ctypes.c_double(), ctypes.c_double()
            left, right, bottom, top = (ctypes.c_double() for _ in range(4))
            has_size = bool(lib.FPDFTextObj_GetFontSize(obj, ctypes.byref(size)))
            has_matrix = bool(lib.FPDFPageObj_GetMatrix(obj, ctypes.byref(matrix)))
            if not has_size or not has_matrix:
                raise AssertionError(f'PDFium must return source text glyph {index} font size/matrix: {has_size}/{has_matrix}')
            if not lib.FPDFText_GetCharOrigin(text_page, index, ctypes.byref(x), ctypes.byref(y)) or not lib.FPDFText_GetCharBox(
                    text_page, index, ctypes.byref(left), ctypes.byref(right), ctypes.byref(bottom), ctypes.byref(top)):
                raise AssertionError('source text glyph bounds must be readable')
            result.append({'index': index, 'text': value,
                           'font': name.value.decode(), 'font_size': size.value,
                           'matrix': tuple(getattr(matrix, key) for key in ('a', 'b', 'c', 'd', 'e', 'f')),
                           'origin': (x.value, y.value), 'box': (left.value, bottom.value, right.value, top.value)})
        return result
    finally:
        if text_page:
            lib.FPDFText_ClosePage(text_page)
        if page:
            lib.FPDF_ClosePage(page)
        if document:
            lib.FPDF_CloseDocument(document)
        lib.FPDF_DestroyLibrary()


def pdfium_text_style_snapshot(path, page_index=0):
    """Capture each character's source font and PDF fill/stroke style."""
    lib = ctypes.CDLL(str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib/libpdfium.dylib'))
    pointer = ctypes.c_void_p
    signatures = {
        'FPDF_LoadDocument': ([ctypes.c_char_p, ctypes.c_char_p], pointer),
        'FPDF_LoadPage': ([pointer, ctypes.c_int], pointer),
        'FPDFText_LoadPage': ([pointer], pointer),
        'FPDFText_CountChars': ([pointer], ctypes.c_int),
        'FPDFText_GetUnicode': ([pointer, ctypes.c_int], ctypes.c_uint),
        'FPDFText_GetTextObject': ([pointer, ctypes.c_int], pointer),
        'FPDFTextObj_GetFont': ([pointer], pointer),
        'FPDFPageObj_GetMatrix': ([pointer, ctypes.POINTER(PdfiumMatrix)], ctypes.c_int),
        'FPDFTextObj_GetTextRenderMode': ([pointer], ctypes.c_int),
        'FPDFPageObj_GetFillColor': ([pointer] + [ctypes.POINTER(ctypes.c_uint)] * 4, ctypes.c_int),
        'FPDFPageObj_GetStrokeColor': ([pointer] + [ctypes.POINTER(ctypes.c_uint)] * 4, ctypes.c_int),
        'FPDFFont_GetBaseFontName': ([pointer, pointer, ctypes.c_size_t], ctypes.c_size_t),
        'FPDFText_ClosePage': ([pointer], None),
        'FPDF_ClosePage': ([pointer], None),
        'FPDF_CloseDocument': ([pointer], None),
        'FPDF_DestroyLibrary': ([], None),
    }
    for name, (arguments, result) in signatures.items():
        function = getattr(lib, name)
        function.argtypes = arguments
        function.restype = result
    lib.FPDF_InitLibrary()
    document = page = text_page = None
    try:
        document = lib.FPDF_LoadDocument(str(path).encode(), None)
        if not document:
            raise AssertionError('style fixture must load in PDFium')
        page = lib.FPDF_LoadPage(document, page_index)
        text_page = lib.FPDFText_LoadPage(page)
        result = []
        for index in range(lib.FPDFText_CountChars(text_page)):
            codepoint = lib.FPDFText_GetUnicode(text_page, index)
            obj = lib.FPDFText_GetTextObject(text_page, index)
            if not obj:
                raise AssertionError(f'PDFium must map text character {index} to its source object')
            font = lib.FPDFTextObj_GetFont(obj)
            name = ctypes.create_string_buffer(lib.FPDFFont_GetBaseFontName(font, None, 0))
            lib.FPDFFont_GetBaseFontName(font, name, len(name))
            matrix = PdfiumMatrix()
            if not lib.FPDFPageObj_GetMatrix(obj, ctypes.byref(matrix)):
                raise AssertionError(f'PDFium must return text character {index} transform')

            def color(getter):
                channels = [ctypes.c_uint() for _ in range(4)]
                if not getter(obj, *(ctypes.byref(channel) for channel in channels)):
                    return None
                return tuple(channel.value for channel in channels)

            result.append({'text': chr(codepoint), 'font': name.value.decode().lower(),
                           'fill': color(lib.FPDFPageObj_GetFillColor),
                           'stroke': color(lib.FPDFPageObj_GetStrokeColor),
                           'render_mode': lib.FPDFTextObj_GetTextRenderMode(obj),
                           'matrix': tuple(getattr(matrix, field) for field in ('a', 'b', 'c', 'd', 'e', 'f'))})
        return result
    finally:
        if text_page:
            lib.FPDFText_ClosePage(text_page)
        if page:
            lib.FPDF_ClosePage(page)
        if document:
            lib.FPDF_CloseDocument(document)
        lib.FPDF_DestroyLibrary()


def toc_glyph_snapshot(path, page_index=0, page_number='387'):
    """Return PDFium's source-font and user-space geometry for one TOC suffix."""
    lib = ctypes.CDLL(str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib/libpdfium.dylib'))
    pointer = ctypes.c_void_p
    signatures = {
        'FPDF_LoadDocument': ([ctypes.c_char_p, ctypes.c_char_p], pointer),
        'FPDF_LoadPage': ([pointer, ctypes.c_int], pointer),
        'FPDFText_LoadPage': ([pointer], pointer),
        'FPDFText_CountChars': ([pointer], ctypes.c_int),
        'FPDFText_GetUnicode': ([pointer, ctypes.c_int], ctypes.c_uint),
        'FPDFText_GetTextObject': ([pointer, ctypes.c_int], pointer),
        'FPDFText_GetCharOrigin': ([pointer, ctypes.c_int, ctypes.POINTER(ctypes.c_double), ctypes.POINTER(ctypes.c_double)], ctypes.c_int),
        'FPDFText_GetCharBox': ([pointer, ctypes.c_int] + [ctypes.POINTER(ctypes.c_double)] * 4, ctypes.c_int),
        'FPDFPageObj_GetMatrix': ([pointer, ctypes.POINTER(PdfiumMatrix)], ctypes.c_int),
        'FPDFTextObj_GetFont': ([pointer], pointer),
        'FPDFTextObj_GetFontSize': ([pointer, ctypes.POINTER(ctypes.c_float)], ctypes.c_int),
        'FPDFFont_GetBaseFontName': ([pointer, pointer, ctypes.c_size_t], ctypes.c_size_t),
        'FPDFText_ClosePage': ([pointer], None),
        'FPDF_ClosePage': ([pointer], None),
        'FPDF_CloseDocument': ([pointer], None),
        'FPDF_DestroyLibrary': ([], None),
    }
    for name, (arguments, result) in signatures.items():
        function = getattr(lib, name)
        function.argtypes = arguments
        function.restype = result
    lib.FPDF_InitLibrary()
    document = page = text_page = None
    try:
        document = lib.FPDF_LoadDocument(str(path).encode(), None)
        if not document:
            raise AssertionError('TOC fixture must load in PDFium')
        page = lib.FPDF_LoadPage(document, page_index)
        text_page = lib.FPDFText_LoadPage(page)
        count = lib.FPDFText_CountChars(text_page)
        codepoints = [lib.FPDFText_GetUnicode(text_page, i) for i in range(count)]
        digits = [ord(char) for char in page_number]
        matches = [i for i in range(count - len(digits) + 1) if codepoints[i:i + len(digits)] == digits]
        if len(matches) != 1:
            raise AssertionError(f'Expected exactly one {page_number} page reference, got {len(matches)}')

        def character(index):
            x, y = ctypes.c_double(), ctypes.c_double()
            left, right, bottom, top = (ctypes.c_double() for _ in range(4))
            if not lib.FPDFText_GetCharOrigin(text_page, index, ctypes.byref(x), ctypes.byref(y)):
                raise AssertionError('PDFium must return TOC character origin')
            if not lib.FPDFText_GetCharBox(text_page, index, ctypes.byref(left), ctypes.byref(right),
                                           ctypes.byref(bottom), ctypes.byref(top)):
                raise AssertionError('PDFium must return TOC character bounds')
            obj = lib.FPDFText_GetTextObject(text_page, index)
            font = lib.FPDFTextObj_GetFont(obj)
            name_size = lib.FPDFFont_GetBaseFontName(font, None, 0)
            name = ctypes.create_string_buffer(name_size)
            lib.FPDFFont_GetBaseFontName(font, name, len(name))
            size = ctypes.c_float()
            matrix = PdfiumMatrix()
            self_ok = lib.FPDFTextObj_GetFontSize(obj, ctypes.byref(size)) and lib.FPDFPageObj_GetMatrix(obj, ctypes.byref(matrix))
            if not self_ok:
                raise AssertionError('PDFium must return source text-object matrix and font size')
            result = {'codepoint': codepoints[index], 'origin': (x.value, y.value),
                      'box': (left.value, bottom.value, right.value, top.value),
                      'font': name.value.decode(), 'font_size': size.value,
                      'matrix': tuple(getattr(matrix, field) for field in ('a','b','c','d','e','f'))}
            return result

        number = [character(i) for i in range(matches[0], matches[0] + len(digits))]
        baseline = number[0]['origin'][1]
        dots = []
        for i, cp in enumerate(codepoints):
            if cp != ord('.'):
                continue
            glyph = character(i)
            if abs(glyph['origin'][1] - baseline) < 0.05 and glyph['origin'][0] < number[0]['origin'][0]:
                dots.append(glyph)
        return {'number': number, 'dots': dots}
    finally:
        if text_page:
            lib.FPDFText_ClosePage(text_page)
        if page:
            lib.FPDF_ClosePage(page)
        if document:
            lib.FPDF_CloseDocument(document)
        lib.FPDF_DestroyLibrary()


def navigation_pdf(path):
    def stream(data):
        return b'<< /Length ' + str(len(data)).encode() + b' >>\nstream\n' + data + b'\nendstream'
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R /Names << /Dests 8 0 R >> /Outlines 9 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [10 15 602 780] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R /Annots [11 0 R] >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [10 15 602 780] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        stream(b'BT /F1 16 Tf 40 720 Td (First page) Tj ET 1 0 0 RG 40 600 180 50 re S'),
        stream(b'BT /F1 16 Tf 40 720 Td (Second page) Tj ET'),
        b'<< /Names [(chapter-two) [4 0 R /XYZ 20 700 null]] >>',
        b'<< /Type /Outlines /First 10 0 R /Last 10 0 R /Count 1 >>',
        b'<< /Title (Second chapter) /Parent 9 0 R /Dest (chapter-two) >>',
        b'<< /Type /Annot /Subtype /Link /Rect [40 650 220 680] /Border [0 0 0] /Dest [4 0 R /XYZ 20 700 null] >>',
    ]
    data = b'%PDF-1.7\n'; offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data)); data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    start = len(data)
    data += f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += f'trailer << /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()
    path.write_bytes(data)


class SourcePreservingPdfTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.source = self.directory / 'source.pdf'
        self.output = self.directory / 'translated.pdf'
        sample_pdf(self.source)

    def tearDown(self):
        self.temp.cleanup()

    def request(self, index=0):
        blocks = run('page', self.source, index)['blocks']
        for block in blocks:
            block['translatedText'] = 'Коротко.'
            block['status'] = 'translated'
        return dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=index, blocks=blocks)

    def export(self, request, index=0, output=None, cjk_font=None, font=FONT):
        arguments = ['export', self.source, index, json.dumps(request, ensure_ascii=False), output or self.output, font]
        if cjk_font is not None:
            arguments.append(cjk_font)
        return run(*arguments)

    def table_request(self, values=None, navigation=False):
        table_cells_pdf(self.source, values=values, navigation=navigation)
        page_count = 2 if navigation else 1
        page = run('page', self.source, 0)
        blocks = page['blocks']
        expected = values or ['Fan', '15', 'Rear']
        self.assertEqual(len(expected), len(blocks), 'one source text object must become one unit per ruled cell')
        indices = blocks[0]['objectIndices']
        self.assertEqual(1, len(indices))
        spans = []
        for block, source_text in zip(blocks, expected):
            self.assertEqual(source_text, block['sourceText'])
            self.assertEqual(indices, block['objectIndices'])
            self.assertEqual(1, len(block['sourceSpans']))
            self.assertEqual(indices[0], block['sourceSpans'][0]['objectIndex'])
            spans.extend(block['sourceSpans'][0]['characterIndices'])
            block['translatedText'] = source_text
            block['status'] = 'translated'
        self.assertEqual(len(spans), len(set(spans)), 'each source character must belong to only one virtual cell')
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=blocks)
        requests = [request]
        for index in range(1, page_count):
            other = run('page', self.source, index)['blocks']
            for block in other:
                block['translatedText'] = block['sourceText']
                block['status'] = 'translated'
            requests.append(dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=index, blocks=other))
        return requests

    def toc_request(self, wrapped=False, label=None):
        toc_pdf(self.source, wrapped=wrapped, label=label)
        page = run('page', self.source, 0)
        blocks = page['blocks']
        self.assertEqual(1, len(blocks), 'all visible fragments of a TOC entry form one translation unit')
        block = blocks[0]
        expected_label = 'Reporting safety defects to owners' if wrapped else label or 'Reporting Safety Defects'
        self.assertEqual(expected_label, block.get('modelText'))
        self.assertIn(expected_label, block['sourceText'])
        self.assertIn('387', block['sourceText'])
        # A TOC row may legitimately contain that number in its section title;
        # only the dotted navigation leader is metadata excluded from inference.
        self.assertNotRegex(block['modelText'], r'\.{3,}')
        self.assertGreaterEqual(len(block['objectIndices']), 1 if not wrapped else 3)
        block['translatedText'] = ('Раздел 387' if label else 'Сообщить о дефектах') if not wrapped else 'Сообщить владельцам о дефектах безопасности'
        block['status'] = 'translated'
        return dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=blocks)

    def pictogram_request(self, content, translate):
        pictogram_pdf(self.source, content)
        blocks = run('page', self.source, 0)['blocks']
        self.assertTrue(blocks, 'fixture must yield source-owned text blocks')
        for block in blocks:
            tokens = [symbol['token'] for symbol in block.get('symbols', [])]
            block['translatedText'] = translate(block, tokens)
            block['status'] = 'translated'
        return dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=blocks)

    def stationary_collision_request(self):
        from reportlab.pdfbase import pdfmetrics

        prefix = 'Before driving carefully, check '
        source_x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 12) + 2
        content = (b'BT /F1 12 Tf 70 700 Td (' + prefix.encode('ascii') + b') Tj ET '
                   b'BT /F2 12 Tf ' + f'{source_x:.3f}'.encode('ascii') + b' 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf ' + f'{source_x + 14:.3f}'.encode('ascii') + b' 700 Td (tires.) Tj ET '
                   b'BT /F1 9 Tf 70 690 Td (Check pressure.) Tj ET '
                   b'BT /F1 9 Tf 70 680 Td (Stay safe.) Tj ET '
                   b'BT /F1 9 Tf 70 670 Td (Stop.) Tj ET '
                   b'BT /F2 24 Tf 280 678 Td (l) Tj ET')
        target = ('Перед поездкой проверьте шины {token}. Убедитесь, что давление соответствует нагрузке. '
                  'Сверьтесь с руководством о норме давления. При повреждении остановитесь безопасно '
                  'и обратитесь за помощью.')

        def translate(block, tokens):
            if block['sourceText'].startswith('Before'):
                return target.format(token=tokens[0])
            return tokens[0] if tokens else 'Давление проверьте.'

        request = self.pictogram_request(content, translate)
        text_rows = [block for block in request['blocks'] if block['sourceText'].strip() != '●']
        stationary_block = next(block for block in request['blocks'] if block['sourceText'].strip() == '●')
        main = dict(text_rows[0])
        main['id'] = 'multirow-text-with-inline-symbol'
        main['sourceText'] = ' '.join(block['sourceText'] for block in text_rows)
        main['modelText'] = ' '.join(block['modelText'] for block in text_rows)
        main['objectIndices'] = sorted({index for block in text_rows for index in block['objectIndices']})
        main['sourceRects'] = [rect for block in text_rows for rect in block['sourceRects']]
        main['left'] = min(rect['left'] for rect in main['sourceRects'])
        main['top'] = min(rect['top'] for rect in main['sourceRects'])
        main['right'] = max(rect['right'] for rect in main['sourceRects'])
        main['bottom'] = max(rect['bottom'] for rect in main['sourceRects'])
        main['translatedText'] = target.format(token=main['symbols'][0]['token'])
        request['blocks'] = [main, stationary_block]
        return request, target, stationary_block['symbols'][0]['bounds']

    def assert_nontext_artwork_unchanged(self):
        from pypdf import PdfReader
        before, after = PdfReader(self.source), PdfReader(self.output)
        self.assertEqual(1, len(before.pages))
        self.assertEqual(1, len(after.pages))
        self.assertEqual(before.pages[0].mediabox, after.pages[0].mediabox)
        self.assertEqual(before.pages[0].cropbox, after.pages[0].cropbox)
        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        self.assertEqual([obj for obj in source['objects'] if obj[0] != 1],
                         [obj for obj in output['objects'] if obj[0] != 1])
        pixel = (792 - 620) * source['stride'] + 100 * 4
        self.assertEqual(source['pixels'][pixel:pixel + 4], output['pixels'][pixel:pixel + 4])

    def assert_pictogram_preservation(self, source, output, moved):
        before = pdfium_symbol_snapshot(source)
        after = pdfium_symbol_snapshot(output)
        self.assertEqual(len(before), len(after))
        self.assertEqual(len(moved), len(before))
        for original, target, should_move in zip(before, after, moved):
            for key in ('font', 'font_size', 'colors', 'render_mode', 'stroke_width'):
                self.assertEqual(original[key], target[key], f'original pictogram {key} is preserved')
            # PDFium normalizes the trailing spacing in Type1 Dingbats text
            # objects when rebuilding a page; the mapped glyph itself must
            # remain the same.
            self.assertEqual(original['text'].strip(), target['text'].strip(),
                             'original pictogram glyph is preserved')
            for index in range(4):
                self.assertAlmostEqual(original['matrix'][index], target['matrix'][index], places=5)
            if should_move:
                self.assertGreater(abs(original['matrix'][4] - target['matrix'][4]) +
                                   abs(original['matrix'][5] - target['matrix'][5]), 1.0,
                                   'verified inline symbol follows flowed target text')
                bounds = pdfium_snapshot(output, allow_graphics=True, dimensions=(612, 792))['objects']
                self.assertTrue(any(item[0] == 1 and item[1].lower().find(b'dingbat') >= 0 and
                                    50 <= item[3][0] <= 300 for item in bounds),
                                'flowed inline pictogram stays inside its source text column')
            else:
                for index in (4, 5):
                    self.assertAlmostEqual(original['matrix'][index], target['matrix'][index], places=3,
                                           msg='standalone or ambiguous pictogram retains its source position')

    def assert_toc_source_art_unchanged(self):
        from pypdf import PdfReader
        before, after = PdfReader(self.source), PdfReader(self.output)
        self.assertEqual(1, len(after.pages))
        self.assertEqual(before.pages[0].mediabox, after.pages[0].mediabox)
        self.assertEqual(before.pages[0].cropbox, after.pages[0].cropbox)
        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        translated = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        self.assertEqual([item for item in source['objects'] if item[0] != 1],
                         [item for item in translated['objects'] if item[0] != 1])
        self.assertEqual(1, sum(item[0] == 2 for item in translated['objects']))
        # The changed TOC occupies only the top band; the isolated vector mark
        # and every other source pixel must remain identical.
        for row in list(range(0, 55)) + list(range(145, 792)):
            start = row * source['stride']
            end = start + source['stride']
            self.assertEqual(source['pixels'][start:end], translated['pixels'][start:end],
                             f'source artwork changed outside the TOC band at bitmap row {row}')

    def assert_toc_suffix_geometry_unchanged(self, source, output, require_leader=True):
        original = toc_glyph_snapshot(source)
        translated = toc_glyph_snapshot(output)
        self.assertEqual(len(original['number']), len(translated['number']))
        for before, after in zip(original['number'], translated['number']):
            self.assertEqual(before['codepoint'], after['codepoint'])
            self.assertEqual(before['font'], after['font'])
            self.assertEqual(before['font_size'], after['font_size'])
            for index in range(4):
                self.assertAlmostEqual(before['matrix'][index], after['matrix'][index], places=5)
            for old, new in zip(before['origin'] + before['box'], after['origin'] + after['box']):
                self.assertAlmostEqual(old, new, places=3)
        old_dots = original['dots']
        new_dots = translated['dots']
        self.assertGreaterEqual(len(old_dots), 3)
        if require_leader:
            self.assertGreaterEqual(len(new_dots), 1)
        self.assertLessEqual(len(new_dots), len(old_dots), 'translation may remove only source-positioned leaders')
        remaining = [(glyph['origin'], glyph['box']) for glyph in old_dots]
        for glyph in new_dots:
            match = next((index for index, (origin, box) in enumerate(remaining)
                          if all(abs(a - b) < 0.03 for a, b in zip(origin, glyph['origin']))
                          and all(abs(a - b) < 0.03 for a, b in zip(box, glyph['box']))), None)
            self.assertIsNotNone(match, 'each output leader dot must occupy an original source position')
            remaining.pop(match)

    def test_single_object_contents_row_sends_only_label_and_retains_suffix_glyphs(self):
        request = self.toc_request()
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertEqual(1, result['pageCount'])
        from pypdf import PdfReader
        text = PdfReader(self.output).pages[0].extract_text()
        self.assertIn('Сообщить о дефектах', text)
        self.assertIn('387', text)
        self.assertNotIn('Reporting Safety Defects', text)
        self.assert_toc_source_art_unchanged()
        self.assert_toc_suffix_geometry_unchanged(self.source, self.output)

    def test_wrapped_mixed_object_contents_row_keeps_original_page_reference_and_dots(self):
        request = self.toc_request(wrapped=True)
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertEqual(1, result['pageCount'])
        from pypdf import PdfReader
        text = PdfReader(self.output).pages[0].extract_text()
        self.assertIn('Сообщить владельцам о дефектах безопасности', text)
        self.assertIn('387', text)
        self.assertNotIn('Reporting safety', text)
        self.assertNotIn('defects to owners', text)
        self.assert_toc_source_art_unchanged()
        self.assert_toc_suffix_geometry_unchanged(self.source, self.output)

    def test_overlong_contents_label_rejects_atomically_without_replacing_prior_pdf(self):
        request = self.toc_request()
        request['blocks'][0]['translatedText'] = 'Чрезвычайно длинный перевод раздела ' * 100
        self.output.write_bytes(b'previous verified TOC export')
        result = self.export(request)
        self.assertIn('error', result)
        self.assertEqual(b'previous verified TOC export', self.output.read_bytes())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_contents_translation_keeps_semantic_digits_matching_page_reference(self):
        request = self.toc_request(label='Section 387')
        self.assertEqual('Section 387', request['blocks'][0]['modelText'])
        self.assertEqual('Раздел 387', request['blocks'][0]['translatedText'])
        result = self.export(request)
        self.assertNotIn('error', result)
        from pypdf import PdfReader
        text = PdfReader(self.output).pages[0].extract_text()
        self.assertIn('Раздел 387', text, 'the target label keeps its own semantic number')
        self.assertIn('387', text, 'the original source page reference remains visible')
        self.assertNotIn('Section 387', text)
        self.assertEqual(2, text.count('387'), 'one target number and one source page reference remain')

    def test_overlong_multiline_contents_returns_capacity_instead_of_missing_glyph(self):
        request = self.toc_request()
        request['blocks'][0]['translatedText'] = ('Очень длинное полное название\nраздела безопасности\t' * 80)
        result = self.export(request)
        self.assertIn('error', result)
        self.assertNotIn('lacks a required glyph', result['error'])
        self.assertIn('layoutFeedback', result)
        self.assertGreater(result['layoutFeedback']['estimatedMaxCharacters'], 0)

    def test_condensed_font_fits_complete_safety_heading_without_lowering_readable_floor(self):
        request = self.toc_request()
        target = 'Сообщение о выявленных недостатках безопасности'
        request['blocks'][0]['translatedText'] = target
        regular = self.export(request)
        self.assertIn('error', regular, 'the ordinary font must reproduce the constrained heading failure')
        condensed = self.export(request, font=ROOT / 'assets/fonts/NotoSansCondensed-Regular.ttf')
        self.assertNotIn('error', condensed)
        self.assertIs(condensed['partial'], False)
        from pypdf import PdfReader
        self.assertIn(target, PdfReader(self.output).pages[0].extract_text())
        source_size = request['blocks'][0]['fontSize']
        minimum = min(source_size, max(6, source_size * .8))
        placements = [p for p in condensed['placements'] if p['role'] == 'text']
        self.assertTrue(placements)
        self.assertTrue(all(p['fontSize'] >= minimum - .001 for p in placements))
        self.assert_toc_source_art_unchanged()
        # A full label may consume every leader dot; source page-number glyphs
        # and any remaining dots must still retain their exact source geometry.
        self.assert_toc_suffix_geometry_unchanged(self.source, self.output, require_leader=False)

    def test_verified_inline_pictogram_flows_with_paragraph_inside_its_column(self):
        content = (b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 112 700 Td (now to continue reading the instructions) Tj ET '
                   b'BT /F1 12 Tf 70 685 Td (and confirm the control is responding.) Tj ET')
        request = self.pictogram_request(content, lambda block, tokens: (
            f'Нажмите {tokens[0]} и убедитесь, что он работает.'
            if tokens else 'Нажмите и убедитесь, что он работает.'))
        self.assertEqual(1, len(request['blocks']))
        block = request['blocks'][0]
        self.assertEqual(1, len(block['symbols']))
        token = block['symbols'][0]['token']
        self.assertIn(token, block['modelText'])
        self.assertLess(block['modelText'].index(token), block['modelText'].rindex('reading'))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assert_pictogram_preservation(self.source, self.output, [True])
        self.assert_nontext_artwork_unchanged()
        from pypdf import PdfReader
        text = PdfReader(self.output).pages[0].extract_text()
        self.assertIn('работает.', text)
        self.assertNotIn('Press now', text)

    def test_moving_foreign_inline_glyph_does_not_shrink_a_valid_heading_line(self):
        content = (b'BT /F1 12 Tf 70 712 Td (Air Controls) Tj ET '
                   b'BT /F1 9 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 100 700 Td (l) Tj ET '
                   b'BT /F1 9 Tf 114 700 Td (before starting the procedure) Tj ET '
                   b'BT /F1 9 Tf 70 688 Td (and follow each step carefully.) Tj ET')

        def translate(block, tokens):
            if tokens:
                return (f'Нажмите {tokens[0]} перед началом процедуры, затем выполните все указанные '
                        'действия по порядку и проверьте результат.')
            return 'Система вентиляции'

        request = self.pictogram_request(content, translate)
        self.assertEqual(2, len(request['blocks']), 'the heading and icon paragraph have separate owners')
        heading = next(block for block in request['blocks'] if block['sourceText'] == 'Air Controls')
        moving = next(block for block in request['blocks'] if block.get('symbols'))
        self.assertEqual(1, len(moving['symbols']))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])

        heading_lines = [item for item in result['placements']
                         if item['blockId'] == heading['id'] and item['role'] == 'text']
        self.assertEqual(1, len(heading_lines), 'the short heading must remain one line')
        self.assertAlmostEqual(12, heading_lines[0]['fontSize'], places=3)
        self.assertAlmostEqual(1, heading_lines[0]['widthScale'], places=3)
        self.assertTrue(any(item['blockId'] == moving['id'] and item['role'] == 'inline-source-glyph'
                            for item in result['placements']),
                        'the separately owned inline pictogram still follows its translated paragraph')
        self.assert_pictogram_preservation(self.source, self.output, [True])
        self.assert_nontext_artwork_unchanged()

    def test_stationary_foreign_glyph_collision_replans_or_fails_atomically(self):
        from pypdf import PdfReader
        request, target, symbol_bounds = self.stationary_collision_request()
        self.assertEqual(2, len(request['blocks']), 'the stationary icon must be a separate source block')
        foreign = next(block for block in request['blocks'] if block['sourceText'].strip() == '●')
        self.assertEqual(1, len(foreign['symbols']))
        self.assertEqual(symbol_bounds, foreign['symbols'][0]['bounds'])

        previous = b'previous verified PDF must survive an unsafe layout'
        self.output.write_bytes(previous)
        result = self.export(request)
        if result.get('error'):
            self.assertEqual(previous, self.output.read_bytes(), 'unsafe replan must fail before publication')
            self.assertFalse(Path(str(self.output) + '.preserving').exists())
            self.assertFalse(Path(str(self.output) + '.preserving.part').exists())
            return

        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assert_pictogram_preservation(self.source, self.output, [False, False])
        self.assert_nontext_artwork_unchanged()

        # Require the rendered foreign-glyph cell to stay pixel-identical. This
        # catches text painted through a Dingbats glyph even when layout boxes
        # merely touch or the PDF export reports no structural issues.
        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        left, right = int(symbol_bounds['left']), int(symbol_bounds['right']) + 1
        top, bottom = int(symbol_bounds['top']), int(symbol_bounds['bottom']) + 1
        for row in range(top, bottom):
            start, end = row * source['stride'] + left * 4, row * source['stride'] + right * 4
            out_start, out_end = row * output['stride'] + left * 4, row * output['stride'] + right * 4
            self.assertEqual(source['pixels'][start:end], output['pixels'][out_start:out_end],
                             'translated ink must not cover the separate stationary source glyph')
        from pypdf import PdfReader
        rendered_text = PdfReader(self.output).pages[0].extract_text()
        self.assertIn('обратитесь за помощью', rendered_text)
        self.assertNotIn('Before driving carefully', rendered_text)

    def test_legacy_object_index_stationary_glyph_remains_a_collision_obstacle(self):
        from pypdf import PdfReader
        for shape in ('missing-array', 'empty-array'):
            with self.subTest(legacy=shape):
                request, _, symbol_bounds = self.stationary_collision_request()
                foreign = next(block for block in request['blocks'] if block['sourceText'].strip() == '●')
                self.assertIn('objectIndex', foreign)
                if shape == 'missing-array':
                    foreign.pop('objectIndices', None)
                else:
                    foreign['objectIndices'] = []
                result = self.export(request)
                self.assertNotIn('error', result)
                self.assertFalse(result['partial'])
                self.assertEqual([], result['issues'])
                self.assert_pictogram_preservation(self.source, self.output, [False, False])
                self.assert_nontext_artwork_unchanged()

                source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
                output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
                left, right = int(symbol_bounds['left']), int(symbol_bounds['right']) + 1
                top, bottom = int(symbol_bounds['top']), int(symbol_bounds['bottom']) + 1
                for row in range(top, bottom):
                    start, end = row * source['stride'] + left * 4, row * source['stride'] + right * 4
                    out_start, out_end = row * output['stride'] + left * 4, row * output['stride'] + right * 4
                    self.assertEqual(source['pixels'][start:end], output['pixels'][out_start:out_end],
                                     'legacy objectIndex keeps the stationary symbol in collision validation')
                rendered = PdfReader(self.output).pages[0].extract_text()
                self.assertIn('обратитесь за помощью', rendered)
                self.assertNotIn('Before driving carefully', rendered)

    def test_preexisting_stationary_symbol_overlap_with_unchanged_text_is_not_a_new_collision(self):
        from pypdf import PdfReader

        content = (b'BT /F1 18 Tf 70 700 Td (Press now) Tj ET '
                   b'BT /F2 24 Tf 70 696 Td (l) Tj ET')
        request = self.pictogram_request(
            content,
            lambda block, tokens: tokens[0] if tokens else block['sourceText'])
        text_blocks = [block for block in request['blocks'] if not block.get('symbols')]
        symbol_blocks = [block for block in request['blocks'] if block.get('symbols')]
        self.assertEqual(1, len(text_blocks), 'the unchanged text has its own source owner')
        self.assertEqual(1, len(symbol_blocks), 'the overlapping symbol has a separate source owner')
        self.assertEqual('Press now', text_blocks[0]['sourceText'].strip())
        text_rect = text_blocks[0]['sourceRects'][0]
        symbol_rect = symbol_blocks[0]['symbols'][0]['bounds']
        self.assertGreater(min(text_rect['right'], symbol_rect['right']) -
                           max(text_rect['left'], symbol_rect['left']), .2)
        self.assertGreater(min(text_rect['bottom'], symbol_rect['bottom']) -
                           max(text_rect['top'], symbol_rect['top']), .2)

        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assert_pictogram_preservation(self.source, self.output, [False])
        self.assert_nontext_artwork_unchanged()
        self.assertIn('Press now', PdfReader(self.output).pages[0].extract_text())

    def test_line_descender_bbox_does_not_force_flow_away_from_clear_foreign_symbol(self):
        from pypdf import PdfReader

        # The translated target has ordinary ink at x~81, but its only ink
        # below the regular letter body is the initial descender at x~70. A
        # foreign symbol at x~81 intersects the coarse run box while remaining
        # clear of every painted target glyph.
        content = (b'BT /F1 12 Tf 70 700 Td (gabcdefghijk) Tj ET '
                   b'BT /F2 24 Tf 80 681 Td (l) Tj ET')
        request = self.pictogram_request(
            content,
            lambda block, tokens: tokens[0] if tokens else 'установка')
        text_block = next(block for block in request['blocks'] if not block.get('symbols'))
        symbol_block = next(block for block in request['blocks'] if block.get('symbols'))
        symbol_bounds = symbol_block['symbols'][0]['bounds']

        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        text_line = next(item for item in result['placements']
                         if item['blockId'] == text_block['id'] and item['role'] == 'text')
        self.assertGreater(min(text_line['right'], symbol_bounds['right']) -
                           max(text_line['left'], symbol_bounds['left']), .2,
                           'the whole target-line box overlaps the icon horizontally')
        self.assertGreater(min(text_line['bottom'], symbol_bounds['bottom']) -
                           max(text_line['top'], symbol_bounds['top']), .2,
                           'the descender at the far left makes the whole target-line box overlap vertically')
        self.assertAlmostEqual(70.4, text_line['left'], places=1,
                               msg='per-character ink avoids an unnecessary obstacle replan that would move the whole line')
        self.assertAlmostEqual(12, text_line['fontSize'], places=3)
        self.assertAlmostEqual(1, text_line['widthScale'], places=3)

        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        left, right = int(symbol_bounds['left']), int(symbol_bounds['right']) + 1
        top, bottom = int(symbol_bounds['top']), int(symbol_bounds['bottom']) + 1
        for row in range(top, bottom):
            start, end = row * source['stride'] + left * 4, row * source['stride'] + right * 4
            out_start, out_end = row * output['stride'] + left * 4, row * output['stride'] + right * 4
            self.assertEqual(source['pixels'][start:end], output['pixels'][out_start:out_end],
                             'per-character bounds must prevent ink from covering the foreign glyph')
        self.assertIn('установка', PdfReader(self.output).pages[0].extract_text())
        self.assertNotIn('gabcdefghijk', PdfReader(self.output).pages[0].extract_text())

    def test_same_baseline_reference_symbol_can_remove_excessive_fixed_anchor_gap(self):
        from reportlab.pdfbase import pdfmetrics

        prefix = 'See Automatic Transmission '
        symbol_x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 9) + 2
        suffix_x = symbol_x + 10
        content = (b'BT /F1 9 Tf 70 700 Td (' + prefix.encode('ascii') + b') Tj ET '
                   b'BT /F2 9 Tf ' + f'{symbol_x:.3f}'.encode('ascii') + b' 700 Td (l) Tj ET '
                   b'BT /F1 9 Tf ' + f'{suffix_x:.3f}'.encode('ascii') + b' 700 Td (200.) Tj ET')
        request = self.pictogram_request(
            content,
            lambda block, tokens: ('См. АКПП ' + tokens[0] + ' 200.') if tokens else 'Текст.')
        self.assertEqual(1, len(request['blocks']))
        block = request['blocks'][0]
        self.assertEqual(1, len(block.get('symbols', [])))
        self.assertIn('См. АКПП', block['translatedText'])

        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        glyphs = [item for item in result['placements']
                  if item.get('blockId') == block['id'] and item.get('role') == 'inline-source-glyph']
        texts = sorted((item for item in result['placements']
                        if item.get('blockId') == block['id'] and item.get('role') == 'text'),
                       key=lambda item: item['left'])
        self.assertEqual(1, len(glyphs), 'eligible same-baseline reference icon can flow with its sentence')
        self.assertEqual(2, len(texts), 'the icon splits text into a prefix and page-reference suffix')
        gap = texts[1]['left'] - texts[0]['right'] - (glyphs[0]['right'] - glyphs[0]['left'])
        self.assertLess(gap, 3 * 9, 'flow removes the fixed-anchor gap without shrinking below the source size')
        for placement in texts:
            self.assertAlmostEqual(9, placement['fontSize'], places=3)
            self.assertAlmostEqual(1, placement['widthScale'], places=3)
        self.assert_pictogram_preservation(self.source, self.output, [True])

    def test_short_same_baseline_caption_keeps_fixed_anchor_at_or_below_three_em(self):
        from reportlab.pdfbase import pdfmetrics

        prefix = 'Press '
        symbol_x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 9) + 2
        suffix_x = symbol_x + 10
        content = (b'BT /F1 9 Tf 70 700 Td (' + prefix.encode('ascii') + b') Tj ET '
                   b'BT /F2 9 Tf ' + f'{symbol_x:.3f}'.encode('ascii') + b' 700 Td (l) Tj ET '
                   b'BT /F1 9 Tf ' + f'{suffix_x:.3f}'.encode('ascii') + b' 700 Td (now.) Tj ET')
        request = self.pictogram_request(
            content,
            lambda block, tokens: ('Да ' + tokens[0] + ' теперь.') if tokens else 'Текст.')
        self.assertEqual(1, len(request['blocks']))
        block = request['blocks'][0]
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assertFalse(any(item.get('blockId') == block['id'] and item.get('role') == 'inline-source-glyph'
                             for item in result['placements']), 'short captions must not move their icon')
        texts = sorted((item for item in result['placements']
                        if item.get('blockId') == block['id'] and item.get('role') == 'text'),
                       key=lambda item: item['left'])
        self.assertEqual(2, len(texts))
        original = block['symbols'][0]['bounds']
        gap = original['left'] - texts[0]['right']
        self.assertGreaterEqual(gap, 0)
        self.assertLessEqual(gap, 3 * texts[0]['fontSize'])
        self.assert_pictogram_preservation(self.source, self.output, [False])

    def test_leading_pictogram_label_is_not_reflowed_by_whitespace_preference(self):
        content = (b'BT /F2 12 Tf 70 700 Td (l) Tj ET '
                   b'BT /F1 9 Tf 82 700 Td (Fuse block cover lock) Tj ET')
        request = self.pictogram_request(
            content,
            lambda block, tokens: (tokens[0] + ': Предохранитель блока') if tokens else 'Крышка блока предохранителей')
        self.assertTrue(any(block.get('symbols') for block in request['blocks']))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assertFalse(any(item.get('role') == 'inline-source-glyph' for item in result['placements']),
                         'a leading pictogram label is outside the same-baseline sentence-flow preference')
        self.assert_pictogram_preservation(self.source, self.output, [False])

    def test_descender_collision_uses_bold_target_ink_expansion(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DictionaryObject, NameObject

        content = (b'BT /F1 12 Tf 70 700 Td (gabcdefghijk) Tj ET '
                   b'BT /F2 24 Tf 80 681 Td (l) Tj ET')
        pictogram_pdf(self.source, content)
        writer = PdfWriter(PdfReader(self.source))
        fonts = writer.pages[0]['/Resources']['/Font']
        fonts[NameObject('/F1')] = writer._add_object(DictionaryObject({
            NameObject('/Type'): NameObject('/Font'),
            NameObject('/Subtype'): NameObject('/Type1'),
            NameObject('/BaseFont'): NameObject('/Helvetica-Bold'),
            NameObject('/Encoding'): NameObject('/WinAnsiEncoding'),
        }))
        with self.source.open('wb') as output:
            writer.write(output)

        blocks = run('page', self.source, 0)['blocks']
        for block in blocks:
            symbols = [symbol['token'] for symbol in block.get('symbols', [])]
            block['translatedText'] = symbols[0] if symbols else 'установка'
            block['status'] = 'translated'
        request = {'layoutVersion': 3, 'mode': 'preserve-source',
                   'sourcePageIndex': 0, 'blocks': blocks}
        result = self.export(request)
        self.assertNotIn('error', result)
        target = next(item for item in result['placements'] if item['role'] == 'text')
        symbol = next(block for block in blocks if block.get('symbols'))['symbols'][0]['bounds']
        self.assertGreater(min(target['right'], symbol['right']) - max(target['left'], symbol['left']), .2)
        self.assertGreater(min(target['bottom'], symbol['bottom']) - max(target['top'], symbol['top']), .2)
        self.assertGreater(target['fontSize'], 9.6,
                           'the bold stroke is accounted for without an unnecessary obstacle reroute')

        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        left, right = int(symbol['left']), int(symbol['right']) + 1
        top, bottom = int(symbol['top']), int(symbol['bottom']) + 1
        for row in range(top, bottom):
            start, end = row * source['stride'] + left * 4, row * source['stride'] + right * 4
            out_start, out_end = row * output['stride'] + left * 4, row * output['stride'] + right * 4
            self.assertEqual(source['pixels'][start:end], output['pixels'][out_start:out_end],
                             'bold target ink expansion must not cover the foreign symbol')

    def test_italic_descender_collision_uses_sheared_character_bounds(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DictionaryObject, NameObject

        pictogram_pdf(self.source,
                      b'BT /F1 12 Tf 70 700 Td (gabcdefghijk) Tj ET '
                      b'BT /F2 24 Tf 80 681 Td (l) Tj ET')
        writer = PdfWriter(PdfReader(self.source))
        fonts = writer.pages[0]['/Resources']['/Font']
        fonts[NameObject('/F1')] = writer._add_object(DictionaryObject({
            NameObject('/Type'): NameObject('/Font'),
            NameObject('/Subtype'): NameObject('/Type1'),
            NameObject('/BaseFont'): NameObject('/Helvetica-Oblique'),
            NameObject('/Encoding'): NameObject('/WinAnsiEncoding'),
        }))
        with self.source.open('wb') as output:
            writer.write(output)

        blocks = run('page', self.source, 0)['blocks']
        for block in blocks:
            symbols = [symbol['token'] for symbol in block.get('symbols', [])]
            block['translatedText'] = symbols[0] if symbols else 'abcdefghikl'
            block['status'] = 'translated'
        result = self.export({'layoutVersion': 3, 'mode': 'preserve-source',
                              'sourcePageIndex': 0, 'blocks': blocks})
        self.assertNotIn('error', result)
        target = next(item for item in result['placements'] if item['role'] == 'text')
        symbol = next(block for block in blocks if block.get('symbols'))['symbols'][0]['bounds']
        self.assertGreater(min(target['right'], symbol['right']) - max(target['left'], symbol['left']), .2)
        self.assertGreater(min(target['bottom'], symbol['bottom']) - max(target['top'], symbol['top']), .2)

        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        left, right = int(symbol['left']), int(symbol['right']) + 1
        top, bottom = int(symbol['top']), int(symbol['bottom']) + 1
        for row in range(top, bottom):
            start, end = row * source['stride'] + left * 4, row * source['stride'] + right * 4
            out_start, out_end = row * output['stride'] + left * 4, row * output['stride'] + right * 4
            self.assertEqual(source['pixels'][start:end], output['pixels'][out_start:out_end],
                             'the italic shear must not make a clear glyph look colliding')

    def test_width_scaled_descender_collision_uses_compressed_character_bounds(self):
        from pypdf import PdfReader

        content = (b'BT /F1 12 Tf 70 700 Td (gabcde) Tj ET '
                   b'BT /F2 24 Tf 80 681 Td (l) Tj ET '
                   b'0 0 0 RG 112 620 m 112 730 l S')
        request = self.pictogram_request(
            content,
            lambda block, tokens: tokens[0] if tokens else 'установка')
        result = self.export(request)
        self.assertNotIn('error', result)
        target = next(item for item in result['placements'] if item['role'] == 'text')
        symbol = next(block for block in request['blocks'] if block.get('symbols'))['symbols'][0]['bounds']
        self.assertAlmostEqual(.8, target['widthScale'], places=3,
                               msg='the source-cell divider requires real horizontal compression')
        self.assertGreater(target['fontSize'], 9.6)
        self.assertGreater(min(target['right'], symbol['right']) - max(target['left'], symbol['left']), .2)
        self.assertGreater(min(target['bottom'], symbol['bottom']) - max(target['top'], symbol['top']), .2)

        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        left, right = int(symbol['left']), int(symbol['right']) + 1
        top, bottom = int(symbol['top']), int(symbol['bottom']) + 1
        for row in range(top, bottom):
            start, end = row * source['stride'] + left * 4, row * source['stride'] + right * 4
            out_start, out_end = row * output['stride'] + left * 4, row * output['stride'] + right * 4
            self.assertEqual(source['pixels'][start:end], output['pixels'][out_start:out_end],
                             'compressed target ink must remain clear of the foreign symbol')
        self.assertIn('установка', PdfReader(self.output).pages[0].extract_text())

    def test_verified_inline_symbol_joins_adjacent_crossline_sentence(self):
        from pypdf import PdfReader

        content = (b'BT /F1 12 Tf 70 700 Td (To continue press ) Tj ET '
                   b'BT /F2 12 Tf 70 686 Td (l) Tj ET '
                   b'BT /F1 12 Tf 86 686 Td (now and check that the control responds correctly.) Tj ET')
        request = self.pictogram_request(
            content,
            lambda block, tokens: (f'Чтобы продолжить, нажмите {tokens[0]} и проверьте реакцию системы.'
                                   if tokens else 'Чтобы продолжить, проверьте реакцию системы.'))
        self.assertEqual(1, len(request['blocks']),
                         'ordinary text and a close next-line pictogram remain one source-owned sentence')
        block = request['blocks'][0]
        self.assertEqual(1, len(block['symbols']))
        self.assertIn(block['symbols'][0]['token'], block['modelText'])
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assert_pictogram_preservation(self.source, self.output, [False])
        self.assert_nontext_artwork_unchanged()
        text = PdfReader(self.output).pages[0].extract_text()
        self.assertIn('Чтобы продолжить', text)
        self.assertIn('проверьте реакцию системы', text)
        self.assertNotIn('To continue press', text)

    def test_unsafe_preferred_glyph_flow_uses_fixed_fit_or_rejects_atomically(self):
        from pypdf import PdfReader

        prefix = 'Before starting the trip, inspect each tire for unusual wear'
        # Use ReportLab's Helvetica metrics so the pictogram follows the real
        # source run rather than relying on a guessed source rectangle.
        from reportlab.pdfbase import pdfmetrics
        source_x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 12) + 2
        content = (b'BT /F1 12 Tf 70 700 Td (' + prefix.encode('ascii') + b') Tj ET '
                   b'BT /F2 12 Tf ' + f'{source_x:.3f}'.encode('ascii') + b' 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf ' + f'{source_x + 14:.3f}'.encode('ascii') + b' 700 Td (now.) Tj ET '
                   b'BT /F1 12 Tf 70 685 Td (and confirm the vehicle is safe to drive.) Tj ET')

        def add_art_under_candidate(candidate):
            # Placement bounds use a top-origin page coordinate system; PDF
            # content streams use a bottom-origin coordinate system.
            left = candidate['left'] - .5
            bottom = 792 - candidate['bottom'] - .5
            width = candidate['right'] - candidate['left'] + 1
            height = candidate['bottom'] - candidate['top'] + 1
            return (content + f' 0 0 1 rg {left:.3f} {bottom:.3f} '
                    f'{width:.3f} {height:.3f} re f'.encode('ascii'))

        safe_translation = 'Перед поездкой осмотрите каждую шину на предмет необычного износа'
        control = self.directory / 'preferred-flow-control.pdf'
        control_request = self.pictogram_request(
            content, lambda block, tokens: f'{safe_translation} {tokens[0]} сейчас.' if tokens else safe_translation)
        control_result = self.export(control_request, output=control)
        self.assertNotIn('error', control_result, 'the source paragraph accepts the preferred-flow control')
        candidate = next(item for item in control_result['placements']
                         if item['role'] == 'inline-source-glyph')
        source_right = max(rect['right'] for rect in control_request['blocks'][0]['sourceRects'])
        self.assertGreater(candidate['left'], source_right + 50,
                           'the preferred flow moves the glyph beyond the original paragraph')

        safe_request = self.pictogram_request(
            add_art_under_candidate(candidate),
            lambda block, tokens: f'{safe_translation} {tokens[0]} сейчас.' if tokens else safe_translation)
        safe_result = self.export(safe_request)
        self.assertNotIn('error', safe_result,
                         'an unsafe preferred flow falls back to a valid fixed-anchor layout')
        self.assertFalse(safe_result['partial'])
        self.assertEqual([], safe_result['issues'])
        self.assertFalse(any(item['role'] == 'inline-source-glyph' for item in safe_result['placements']),
                         'the unsafe candidate glyph is not moved into the protected artwork')
        self.assert_pictogram_preservation(self.source, self.output, [False])
        self.assert_nontext_artwork_unchanged()
        safe_text = PdfReader(self.output).pages[0].extract_text()
        self.assertIn(safe_translation, safe_text)
        self.assertNotIn(prefix, safe_text)

        long_translation = ('Перед поездкой внимательно осмотрите каждую шину на предмет необычного износа; '
                            'проверьте давление, найдите повреждения и убедитесь, что автомобиль '
                            'безопасно вести')
        long_control = self.directory / 'preferred-flow-long-control.pdf'
        long_request = self.pictogram_request(
            content, lambda block, tokens: f'{long_translation} {tokens[0]} сейчас.' if tokens else long_translation)
        long_control_result = self.export(long_request, output=long_control)
        self.assertNotIn('error', long_control_result,
                         'the long translation still has a valid flowing layout without the obstacle')
        long_candidate = next(item for item in long_control_result['placements']
                              if item['role'] == 'inline-source-glyph')
        source_symbol = long_request['blocks'][0]['symbols'][0]['bounds']
        self.assertNotAlmostEqual(source_symbol['left'], long_candidate['left'], delta=5,
                                  msg='the flow control must actually move the source glyph')

        blocked_request = self.pictogram_request(
            add_art_under_candidate(long_candidate),
            lambda block, tokens: f'{long_translation} {tokens[0]} сейчас.' if tokens else long_translation)
        source_hash = hashlib.sha256(self.source.read_bytes()).hexdigest()
        prior_export = b'previous verified source-preserving export'
        self.output.write_bytes(prior_export)
        blocked_result = self.export(blocked_request)
        self.assertIn('error', blocked_result,
                      'when both the protected flow and fixed fit are invalid, export must fail')
        self.assertEqual(prior_export, self.output.read_bytes(),
                         'failed fallback leaves the previously published PDF intact')
        self.assertEqual(source_hash, hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_cross_block_overlap_replans_preferred_flow_to_fixed_layout(self):
        prefix = 'Before driving carefully, check '
        suffix = 'tires and check their condition before leaving.'
        from reportlab.pdfbase import pdfmetrics
        source_x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 12) + 2
        translated_prefix = 'Перед поездкой проверьте шины '
        translated_suffix = ('. Убедитесь, что давление соответствует нагрузке. '
                             'Сверьтесь с руководством о норме давления. '
                             'При повреждении остановитесь безопасно и обратитесь за помощью.')

        def fixture(include_neighbor, neighbor_y=665):
            content = (b'BT /F1 12 Tf 70 700 Td (' + prefix.encode('ascii') + b') Tj ET '
                       b'BT /F2 12 Tf ' + f'{source_x:.3f}'.encode('ascii') + b' 700 Td (l) Tj ET '
                       b'BT /F1 12 Tf ' + f'{source_x + 14:.3f}'.encode('ascii') + b' 700 Td (' +
                       suffix.encode('ascii') + b') Tj ET '
                       b'BT /F1 12 Tf 70 690 Td (Confirm pressure is correct for the current load.) Tj ET '
                       b'BT /F1 12 Tf 70 680 Td (Consult the manual for correct pressure and warnings.) Tj ET '
                       b'BT /F1 12 Tf 70 670 Td (If a tire is damaged, stop safely and seek help.) Tj ET')
            if include_neighbor:
                content += (b' BT /F2 24 Tf 70 ' + str(neighbor_y).encode('ascii') +
                            b' Td (l) Tj ET BT /F1 12 Tf 90 ' + str(neighbor_y).encode('ascii') +
                            b' Td (Stop here.) Tj ET')
            request = self.pictogram_request(
                content,
                lambda block, tokens: (f'{translated_prefix}{tokens[0]}{translated_suffix}'
                                       if tokens else 'Остановитесь здесь.'))
            neighbor = next((item for item in request['blocks'] if 'Stop here.' in item['sourceText']), None)
            paragraphs = [item for item in request['blocks'] if item is not neighbor]
            first = dict(paragraphs[0])
            first['id'] = 'multirow-inline-paragraph'
            first['sourceText'] = ' '.join(item['sourceText'] for item in paragraphs)
            first['modelText'] = ' '.join(item['modelText'] for item in paragraphs)
            first['objectIndices'] = sorted({index for item in paragraphs for index in item['objectIndices']})
            first['sourceRects'] = [rect for item in paragraphs for rect in item['sourceRects']]
            first['translatedText'] = f'{translated_prefix}{first["symbols"][0]["token"]}{translated_suffix}'
            if neighbor is not None:
                neighbor_token = next((item['token'] for item in neighbor.get('symbols', [])), None)
                if neighbor_token:
                    neighbor['translatedText'] = f'{neighbor_token} Остановитесь здесь.'
            request['blocks'] = [first] + ([neighbor] if neighbor is not None else [])
            return request

        control = self.directory / 'cross-block-flow-control.pdf'
        flow_request = fixture(False)
        flow_result = self.export(flow_request, output=control)
        self.assertNotIn('error', flow_result)
        self.assertTrue(any(item['role'] == 'inline-source-glyph' for item in flow_result['placements']),
                        'without a neighbor, the readability preference selects inline flow')

        request = fixture(True, 665)
        self.assertEqual(2, len(request['blocks']), 'fixture keeps adjacent paragraph ownership separate')
        self.assertIn('Stop here.', request['blocks'][1]['sourceText'])
        result = self.export(request)
        self.assertNotIn('error', result,
                         'cross-block flow collision retries the page with valid fixed fits preferred')
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assert_pictogram_preservation(self.source, self.output, [False, False])
        self.assert_nontext_artwork_unchanged()
        placements = result['placements']
        for index, first in enumerate(placements):
            for second in placements[index + 1:]:
                if first['blockId'] == second['blockId']:
                    continue
                overlap_x = min(first['right'], second['right']) - max(first['left'], second['left'])
                overlap_y = min(first['bottom'], second['bottom']) - max(first['top'], second['top'])
                self.assertFalse(overlap_x > .2 and overlap_y > .2,
                                 f"page retry must prevent cross-block ink overlap: {first['blockId']} / {second['blockId']}")

    def test_tall_inline_glyph_does_not_collide_with_wrapped_text_line(self):
        from reportlab.pdfbase import pdfmetrics
        prefix = 'Press the control before continuing with the procedure'
        source_x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 9) + 2
        content = (b'BT /F1 9 Tf 70 700 Td (' + prefix.encode('ascii') + b') Tj ET '
                   b'BT /F2 20 Tf ' + f'{source_x:.3f}'.encode('ascii') + b' 700 Td (l) Tj ET '
                   b'BT /F1 9 Tf ' + f'{source_x + 16:.3f}'.encode('ascii') + b' 700 Td (before leaving) Tj '
                   b'0 -10 Td (and read every step before continuing) Tj '
                   b'0 -10 Td (to keep the controls clear and visible) Tj '
                   b'0 -10 Td (while following the procedure in order.) Tj ET')
        before_symbol = 'Перед поездкой осмотрите автомобиль и проверьте органы управления '
        after_symbol = ('и затем убедитесь, что они видны и исправны, свободно доступны '
                        'водителю и готовы к использованию перед началом движения.')
        request = self.pictogram_request(
            content,
            lambda block, tokens: (f'{before_symbol}{tokens[0]} {after_symbol}'
                                   if tokens else f'{before_symbol}{after_symbol}'))
        self.assertGreaterEqual(len(request['blocks']), 2,
                                'fixture must provide paragraph rows to form a tall source container')
        source_blocks = request['blocks']
        block = dict(source_blocks[0])
        block['id'] = 'tall-inline-glyph-paragraph'
        block['sourceText'] = ' '.join(item['sourceText'] for item in source_blocks)
        block['modelText'] = ' '.join(item['modelText'] for item in source_blocks)
        block['objectIndices'] = sorted({index for item in source_blocks for index in item['objectIndices']})
        block['sourceRects'] = [rect for item in source_blocks for rect in item['sourceRects']]
        block['translatedText'] = (f'{before_symbol}{block["symbols"][0]["token"]} '
                                   f'{after_symbol}')
        request['blocks'] = [block]
        self.assertEqual(1, len(block['symbols']))
        self.assertAlmostEqual(20, block['symbols'][0]['fontSize'], delta=.1)
        self.assertAlmostEqual(9, block['sourceRects'][0]['fontSize'], delta=.1)
        result = self.export(request)
        self.assertNotIn('error', result, 'the translated paragraph must fit the combined source rows')
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assertTrue(any(item['role'] == 'inline-source-glyph' for item in result['placements']),
                        'the fixture must exercise a tall glyph in verified flow')
        placements = [item for item in result['placements'] if item['blockId'] == block['id']]
        glyph = next(item for item in placements if item['role'] == 'inline-source-glyph')
        text_lines = [item for item in placements if item['role'] == 'text']
        self.assertGreaterEqual(len(text_lines), 2,
                                'long translated paragraph must wrap to exercise line separation')
        source_glyph_rect = next(rect for rect in block['sourceRects'] if rect.get('pictogram'))
        moved_glyph_baseline = (source_glyph_rect['baseline'] + glyph['top'] - source_glyph_rect['top'])
        same_line = min(text_lines, key=lambda item: abs(
            ((item['top'] + item['bottom']) / 2) - moved_glyph_baseline))
        following_lines = [line for line in text_lines if line is not same_line]
        self.assertTrue(any(glyph['bottom'] > line['top'] for line in following_lines),
                        'the tall icon must extend into a following line’s vertical band')
        for line in following_lines:
            overlap_x = min(glyph['right'], line['right']) - max(glyph['left'], line['left'])
            overlap_y = min(glyph['bottom'], line['bottom']) - max(glyph['top'], line['top'])
            self.assertFalse(overlap_x > .2 and overlap_y > .2,
                             f"tall inline glyph must not collide with another wrapped line: {glyph} / {line}")
        for index, first in enumerate(text_lines):
            for second in text_lines[index + 1:]:
                overlap_x = min(first['right'], second['right']) - max(first['left'], second['left'])
                overlap_y = min(first['bottom'], second['bottom']) - max(first['top'], second['top'])
                self.assertFalse(overlap_x > .2 and overlap_y > .2,
                                 f"same-block wrapped text lines must not overlap: {first} / {second}")

    def test_multicharacter_dingbat_spacing_survives_translation_export_and_merge(self):
        content = (b'1 0 0 rg 102 698 22 12 re f '
                   b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 99 700 Td [(l) -120 (m)] TJ ET '
                   b'BT /F1 12 Tf 145 700 Td (now.) Tj ET '
                   b'0 1 0 rg 116 699 2 10 re f')
        request = self.pictogram_request(content, lambda block, tokens: (
            f'Жми {tokens[0]}.' if tokens else 'Жми.'))
        source_glyphs = [glyph for glyph in pdfium_character_snapshot(self.source)
                         if 'dingbat' in glyph.get('font', '').lower()]
        self.assertEqual(2, len(source_glyphs), 'source icon object must contain two real glyphs')
        first, second = source_glyphs
        source_advance = second['origin'][0] - first['origin'][0]
        first_ink_width = first['box'][2] - first['box'][0]
        self.assertGreater(source_advance, first_ink_width + 1.1,
                           'fixture must exercise explicit nonuniform TJ spacing')

        def source_positions(path):
            return [{key: glyph[key] for key in ('text', 'font', 'font_size', 'matrix', 'origin', 'box')}
                    for glyph in pdfium_character_snapshot(path)
                    if 'dingbat' in glyph.get('font', '').lower()]

        preview = self.directory / 'multichar-preview.pdf'
        self.assertNotIn('error', self.export(request, output=preview))
        self.assertEqual(source_positions(self.source), source_positions(preview),
                         'each source glyph origin and box must survive page replacement')
        source_pixels = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        preview_pixels = pdfium_snapshot(preview, allow_graphics=True, dimensions=(612, 792))
        for row in range(82, 95):
            start, end = row * source_pixels['stride'] + 101 * 4, row * source_pixels['stride'] + 125 * 4
            self.assertEqual(source_pixels['pixels'][start:end], preview_pixels['pixels'][start:end],
                             'the icon and its before/after paint-order controls must remain pixel-identical')

        snapshot = self.directory / 'multichar-blocks.json'
        snapshot.write_text(json.dumps(request, ensure_ascii=False))
        envelope = dict(layoutVersion=3, mode='preserve-source', sourcePath=str(self.source),
                        fontPath=str(FONT), shards=[dict(sourcePageIndex=0, expectedPageCount=1,
                                                         path=str(preview), blocksPath=str(snapshot))])
        merged = run('merge', json.dumps(envelope, ensure_ascii=False), self.output)
        self.assertNotIn('error', merged)
        self.assertEqual(source_positions(self.source), source_positions(self.output),
                         'full-original merge must retain both exact source glyph positions')
        merged_pixels = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        for row in range(82, 95):
            start, end = row * source_pixels['stride'] + 101 * 4, row * source_pixels['stride'] + 125 * 4
            self.assertEqual(source_pixels['pixels'][start:end], merged_pixels['pixels'][start:end],
                             'the original glyph paint order must survive full-document merge')

    def test_symbol_only_block_accepts_exact_token_order_and_rejects_reordering(self):
        content = (b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                   b'BT /F2 12 Tf 112 700 Td (m) Tj ET')
        request = self.pictogram_request(content, lambda block, tokens: ' '.join(tokens))
        self.assertEqual(1, len(request['blocks']))
        block = request['blocks'][0]
        tokens = [symbol['token'] for symbol in block['symbols']]
        self.assertEqual(2, len(tokens))
        self.assertEqual(''.join(tokens), ''.join(block['modelText'].split()),
                         'this translation unit contains only its original icon tokens')
        source_symbols = pdfium_symbol_snapshot(self.source)
        self.assertNotIn('error', self.export(request))
        self.assertEqual(source_symbols, pdfium_symbol_snapshot(self.output),
                         'symbol-only replacement retains each original font and matrix')

        block['translatedText'] = ' '.join(reversed(tokens))
        self.output.write_bytes(b'previous verified export')
        rejected = self.export(request)
        self.assertIn('error', rejected, 'icon-only translation must not reorder source symbols')
        self.assertEqual(b'previous verified export', self.output.read_bytes())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def style_request(self, content, translated):
        mixed_text_style_pdf(self.source, content)
        page = run('page', self.source, 0)
        self.assertEqual(1, len(page['blocks']), 'same-line style runs form one translation unit')
        page['blocks'][0]['translatedText'] = translated
        page['blocks'][0]['status'] = 'translated'
        return page['blocks'][0]

    def assert_target_style(self, text, bold_indices=(), italic=False, fill=(255, 0, 0, 255),
                            stroke_indices=()):
        characters = pdfium_text_style_snapshot(self.output)
        self.assertEqual(text, ''.join(item['text'] for item in characters))
        bold_indices = set(bold_indices)
        stroke_indices = set(stroke_indices)
        for index, character in enumerate(characters):
            self.assertEqual(fill, character['fill'], f'target character {index} retains source fill')
            is_stroked = character['render_mode'] == 2
            self.assertEqual(index in stroke_indices, is_stroked,
                             f'target character {index} has only its assigned fill/stroke style')
            if is_stroked:
                self.assertEqual(fill, character['stroke'], 'synthetic bold stroke matches source fill')
        for index, character in enumerate(characters):
            font_is_bold = 'bold' in character['font']
            if index in bold_indices:
                self.assertTrue(font_is_bold or character['render_mode'] == 2,
                                f'target character {index} preserves bold weight')
            else:
                self.assertFalse(font_is_bold,
                                 f'target character {index} must not inherit bold from another source run')
            if italic:
                self.assertGreater(abs(character['matrix'][2]), 0.1,
                                   f'target character {index} retains italic shear')
            else:
                self.assertLess(abs(character['matrix'][2]), 0.01,
                                f'target character {index} remains upright')

    def test_translated_bold_colon_prefix_keeps_body_regular_and_colors(self):
        content = (b'1 0 0 rg 0 0 1 RG .5 w 2 Tr '
                   b'BT /FB 12 Tf 1 0 0 1 70 700 Tm (Speed:) Tj ET '
                   b'0 0 0 rg 0 G 0 Tr '
                   b'BT /F1 12 Tf 1 0 0 1 109 700 Tm ( Displays vehicle information.) Tj ET')
        target = 'Скорость: Показывает скорость автомобиля.'
        block = self.style_request(content, target)
        result = self.export(dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0,
                                  blocks=[block]))
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        prefix_end = target.index(':') + 1
        self.assert_target_style(target, bold_indices=range(prefix_end),
                                 stroke_indices=range(prefix_end))

    def test_fullwidth_bold_colon_maps_with_embedded_simplified_chinese_fallback(self):
        content = (b'1 0 0 rg 0 0 1 RG .5 w 2 Tr '
                   b'BT /FB 12 Tf 1 0 0 1 70 700 Tm (Speed:) Tj ET '
                   b'0 0 0 rg 0 G 0 Tr '
                   b'BT /F1 12 Tf 1 0 0 1 109 700 Tm ( Displays vehicle information.) Tj ET')
        target = '速度：显示车速。'
        block = self.style_request(content, target)
        result = self.export(dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0,
                                  blocks=[block]),
                             cjk_font=ROOT / 'assets/fonts/NotoSansSC-Regular.ttf')
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        prefix_end = target.index('：') + 1
        self.assert_target_style(target, bold_indices=range(prefix_end),
                                 stroke_indices=range(prefix_end))
        characters = pdfium_text_style_snapshot(self.output)
        self.assertIn('notosanssc', characters[prefix_end - 1]['font'],
                      'the fullwidth colon is drawn from the embedded Simplified Chinese fallback')

    def test_uniform_bold_heading_and_italic_reference_keep_uniform_style(self):
        cases = (
            (b'BT /FB 12 Tf 1 0 0 1 70 700 Tm (Speed settings) Tj ET',
             'Настройки скорости', True, False),
            (b'BT /FI 12 Tf 1 0 0 1 70 700 Tm (See owner manual.) Tj ET',
             'См. руководство владельца.', False, True),
        )
        for content, target, bold, italic in cases:
            with self.subTest(bold=bold, italic=italic):
                block = self.style_request(content, target)
                result = self.export(dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0,
                                          blocks=[block]))
                self.assertNotIn('error', result)
                self.assertFalse(result['partial'])
                self.assert_target_style(target,
                                         bold_indices=range(len(target)) if bold else (),
                                         italic=italic, fill=(0, 0, 0, 255),
                                         stroke_indices=range(len(target)) if bold else ())

    def test_regular_first_then_bold_source_run_does_not_bold_entire_translation(self):
        content = (b'0 0 0 rg 0 G 0 Tr '
                   b'BT /F1 12 Tf 1 0 0 1 70 700 Tm (Speed:) Tj ET '
                   b'BT /FB 12 Tf 1 0 0 1 109 700 Tm ( Displays vehicle information.) Tj ET')
        target = 'Скорость: Показывает скорость автомобиля.'
        block = self.style_request(content, target)
        result = self.export(dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0,
                                  blocks=[block]))
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assert_target_style(target, fill=(0, 0, 0, 255))

    def test_opaque_white_lexical_backdrop_allows_inline_glyph_flow(self):
        content = (b'q 1 1 1 rg 65 688 380 28 re f Q '
                   b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 112 700 Td (now to continue reading the instructions) Tj ET '
                   b'BT /F1 12 Tf 70 685 Td (and confirm the control is responding.) Tj ET')
        request = self.pictogram_request(content, lambda block, tokens: (
            f'Нажмите {tokens[0]} и убедитесь, что он работает.' if tokens else 'Нажмите и проверьте.'))
        self.assertEqual(1, len(request['blocks']))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assert_pictogram_preservation(self.source, self.output, [True])
        self.assert_nontext_artwork_unchanged()

    def test_nonpaper_or_unowned_white_rectangles_protect_source_art(self):
        protected_paths = {
            'colored': b'q .92 .92 .92 rg 65 688 380 28 re f Q ',
            'stroked': b'q 1 1 1 rg 1 0 0 RG 1 w 65 688 380 28 re B Q ',
            'clipped': b'q 65 688 m 445 688 l 310 716 l h W n 1 1 1 rg 65 688 380 28 re f Q ',
            'not-enclosing-prose': b'q 1 1 1 rg 95 694 50 12 re f Q ',
        }
        for name, backdrop in protected_paths.items():
            with self.subTest(backdrop=name):
                content = (backdrop +
                           b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                           b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                           b'BT /F1 12 Tf 112 700 Td (now to continue reading the instructions) Tj ET '
                           b'BT /F1 12 Tf 70 685 Td (and confirm the control is responding.) Tj ET')
                request = self.pictogram_request(content, lambda block, tokens: (
                    f'Нажмите {tokens[0]} и убедитесь, что он работает.'
                    if tokens else 'Нажмите и проверьте.'))
                before = hashlib.sha256(self.source.read_bytes()).hexdigest()
                self.output.write_bytes(b'previous verified export')
                result = self.export(request)
                if 'error' not in result:
                    self.assert_pictogram_preservation(self.source, self.output, [False])
                    self.assert_nontext_artwork_unchanged()
                else:
                    self.assertEqual(b'previous verified export', self.output.read_bytes())
                    self.assertFalse(Path(str(self.output) + '.preserving').exists())
                    self.assertFalse(Path(str(self.output) + '.preserving.part').exists())
                self.assertEqual(before, hashlib.sha256(self.source.read_bytes()).hexdigest())

    def test_leading_warning_and_legend_pictograms_remain_source_anchored(self):
        content = (b'BT /F2 12 Tf 70 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 85 700 Td (: Warning: stop vehicle) Tj ET '
                   b'BT /F2 12 Tf 70 660 Td (m) Tj ET '
                   b'BT /F1 12 Tf 85 660 Td (: Legend: additional information) Tj ET')

        def translate(block, tokens):
            self.assertEqual(1, len(tokens))
            return tokens[0] + (': Предупреждение: остановите автомобиль' if 'Warning' in block['sourceText']
                                else ': Условные обозначения')

        request = self.pictogram_request(content, translate)
        self.assertEqual(2, len(request['blocks']))
        self.assertTrue(all(block['modelText'].startswith(block['symbols'][0]['token'] + ':')
                            for block in request['blocks']))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assert_pictogram_preservation(self.source, self.output, [False, False])
        self.assert_nontext_artwork_unchanged()

    def test_forged_source_rects_cannot_make_leading_warning_flowable(self):
        content = (b'BT /F2 12 Tf 70 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 85 700 Td (: Warning: stop vehicle) Tj ET')
        request = self.pictogram_request(content, lambda block, tokens: tokens[0] + ': Предупреждение')
        block = request['blocks'][0]
        symbol = block['sourceRects'][0]
        forged = dict(symbol, objectIndex=-1, text='Warning ', pictogram=False,
                      left=symbol['left'] - 60, right=symbol['left'] - 2)
        block['sourceRects'] = [forged, *block['sourceRects']]
        token = block['symbols'][0]['token']
        block['translatedText'] = f'Подробное предупреждение: остановите автомобиль и проверьте дорогу {token}'
        self.output.write_bytes(b'previous verified export')
        result = self.export(request)
        if 'error' not in result:
            self.assert_pictogram_preservation(self.source, self.output, [False])
            self.assert_nontext_artwork_unchanged()
        else:
            self.assertEqual(b'previous verified export', self.output.read_bytes(),
                             'unflowable stale geometry must fail without replacing the prior PDF')

    def test_arbitrary_chart_path_disables_inline_glyph_flow_atomically(self):
        content = (b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 112 700 Td (now to continue reading the instructions) Tj ET '
                   b'BT /F1 12 Tf 70 685 Td (and confirm the control is responding.) Tj ET '
                   b'0 0 1 rg 125 690 m 135 700 l 130 706 l h f')
        request = self.pictogram_request(content, lambda block, tokens: (
            f'Нажмите {tokens[0]} и убедитесь, что он работает.'
            if tokens else 'Нажмите и убедитесь, что он работает.'))
        before = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.output.write_bytes(b'previous verified export')
        result = self.export(request)
        self.assertIn('error', result, 'arbitrary source paths must not become flow destinations')
        self.assertEqual(b'previous verified export', self.output.read_bytes())
        self.assertEqual(before, hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_image_overlapping_paragraph_blocks_flow_even_if_icon_is_clear(self):
        content = (b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 112 700 Td (now to continue reading the instructions) Tj ET '
                   b'BT /F1 12 Tf 70 685 Td (and confirm the control is responding.) Tj ET')
        pictogram_pdf(self.source, content, include_image=True)
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(1, len(blocks))
        block = blocks[0]
        tokens = [symbol['token'] for symbol in block.get('symbols', [])]
        block['translatedText'] = f'Нажмите {tokens[0]} и убедитесь, что он работает.'
        block['status'] = 'translated'
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=blocks)
        symbol = block['symbols'][0]['bounds']
        from pypdf import PdfReader
        source_page = PdfReader(self.source).pages[0]
        self.assertIn('/Im1', source_page['/Resources']['/XObject'])
        self.assertLess(symbol['right'], 120, 'the source image begins after the unmoved pictogram')
        before = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.output.write_bytes(b'previous verified export')
        result = self.export(request)
        self.assertIn('error', result, 'an image in the paragraph must block the moved glyph')
        self.assertEqual(b'previous verified export', self.output.read_bytes())
        self.assertEqual(before, hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_ambiguous_boundary_pictogram_defaults_to_its_source_position(self):
        content = b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET BT /F2 12 Tf 99 700 Td (l) Tj ET'
        request = self.pictogram_request(content, lambda block, tokens: 'Жми ' + tokens[0])
        self.assertEqual(1, len(request['blocks']))
        self.assertFalse(request['blocks'][0]['modelText'].startswith(request['blocks'][0]['symbols'][0]['token']))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assert_pictogram_preservation(self.source, self.output, [False])
        self.assert_nontext_artwork_unchanged()

    def test_reordered_inline_symbol_tokens_cannot_publish_over_prior_export(self):
        content = (b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 112 700 Td (then ) Tj ET '
                   b'BT /F2 12 Tf 140 700 Td (m) Tj ET '
                   b'BT /F1 12 Tf 153 700 Td (release.) Tj ET')
        request = self.pictogram_request(content, lambda block, tokens: 'Нажмите ' + tokens[1] + ' затем ' + tokens[0])
        self.assertEqual(1, len(request['blocks']))
        tokens = [symbol['token'] for symbol in request['blocks'][0]['symbols']]
        self.assertEqual(2, len(tokens))
        self.assertLess(request['blocks'][0]['translatedText'].index(tokens[1]),
                        request['blocks'][0]['translatedText'].index(tokens[0]))
        self.output.write_bytes(b'previous verified export')
        result = self.export(request)
        self.assertIn('error', result)
        self.assertEqual(b'previous verified export', self.output.read_bytes())

    def test_overlong_inline_symbol_translation_fails_atomically(self):
        content = (b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf 99 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 112 700 Td (now to continue reading.) Tj ET')
        request = self.pictogram_request(content, lambda block, tokens: ('Чрезвычайно длинный перевод ' * 200) + tokens[0])
        self.output.write_bytes(b'previous verified export')
        result = self.export(request)
        self.assertIn('error', result)
        self.assertEqual(b'previous verified export', self.output.read_bytes())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_original_page_geometry_and_vector_artwork_are_retained(self):
        from pypdf import PdfReader
        before = PdfReader(self.source)
        result = self.export(self.request())
        self.assertNotIn('error', result)
        self.assertEqual((3, 'preserve-source', 1), (result['layoutVersion'], result['mode'], result['pageCount']))
        self.assertFalse(result['partial'])
        after = PdfReader(self.output)
        self.assertEqual(before.pages[0].mediabox, after.pages[0].mediabox)
        self.assertEqual(before.pages[0].cropbox, after.pages[0].cropbox)
        self.assertEqual(1, len(after.pages))
        self.assertEqual(1, sum(o[0] == 2 for o in pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))['objects']))
        text = after.pages[0].extract_text()
        self.assertIn('Коротко.', text)
        self.assertNotIn('A short line.', text)
        self.assertTrue(all(p['outputPageIndex'] == 0 for p in result['placements']))

    def test_source_rectangles_retain_actual_fragment_ownership(self):
        request = self.request()
        for block in request['blocks']:
            self.assertEqual(block['objectIndices'], [r['objectIndex'] for r in block['sourceRects']])
            self.assertEqual(block['sourceText'], ''.join(r['text'] for r in block['sourceRects']))
            self.assertTrue(all(not r['pictogram'] for r in block['sourceRects']))
            self.assertTrue(all(r['top'] < r['baseline'] <= r['bottom'] + 4 for r in block['sourceRects']))

    def test_rotated_table_labels_keep_the_original_baseline_direction(self):
        import math
        import pdfplumber
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject, NumberObject
        for angle, matrix in ((0, '1 0 0 1'), (90, '0 1 -1 0'),
                              (-90, '0 -1 1 0'), (180, '-1 0 0 -1')):
            for page_rotation in (0, 90):
                with self.subTest(angle=angle, page_rotation=page_rotation):
                    sample_pdf(self.source)
                    writer = PdfWriter(PdfReader(self.source))
                    stream = DecodedStreamObject()
                    stream.set_data(f'BT /F1 9 Tf {matrix} 300 400 Tm (Service) Tj ET 1 0 0 RG 100 100 50 50 re S'.encode())
                    writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
                    writer.pages[0][NameObject('/Rotate')] = NumberObject(page_rotation)
                    with self.source.open('wb') as output: writer.write(output)
                    with pdfplumber.open(self.source) as original:
                        direction = original.pages[0].chars[0]['matrix'][:4]
                    result = self.export(self.request())
                    self.assertNotIn('error', result)
                    self.assertEqual(1, result['pageCount'])
                    with pdfplumber.open(self.output) as translated:
                        characters = translated.pages[0].chars
                        self.assertIn('Коротко.', ''.join(c['text'] for c in characters))
                        actual = characters[0]['matrix'][:4]
                    for start in (0, 2):
                        source_norm = math.hypot(*direction[start:start+2])
                        target_norm = math.hypot(*actual[start:start+2])
                        for offset in (0, 1):
                            self.assertAlmostEqual(direction[start+offset]/source_norm,
                                                   actual[start+offset]/target_norm, places=4)

    def test_caption_over_opaque_footer_does_not_inherit_a_hidden_column_divider(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject
        writer = PdfWriter(PdfReader(self.source))
        stream = DecodedStreamObject()
        stream.set_data(b'0 0 0 rg 100 0 1 792 re f 1 1 1 rg 20 100 400 60 re f 0 0 0 rg BT /F1 18 Tf 70 120 Td (A short line.) Tj ET')
        writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
        with self.source.open('wb') as output: writer.write(output)
        result = self.export(self.request())
        self.assertNotIn('error', result)
        self.assertEqual(1, result['pageCount'])

    def test_calibrated_vector_fill_color_survives_text_replacement(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject, DictionaryObject, ArrayObject, FloatObject
        writer = PdfWriter(PdfReader(self.source))
        color = ArrayObject([NameObject('/CalRGB'), DictionaryObject({
            NameObject('/WhitePoint'): ArrayObject([FloatObject(.9505), FloatObject(1), FloatObject(1.089)]),
            NameObject('/Gamma'): ArrayObject([FloatObject(2.2)] * 3)})])
        writer.pages[0]['/Resources'][NameObject('/ColorSpace')] = DictionaryObject({NameObject('/Cs1'): color})
        stream = DecodedStreamObject()
        stream.set_data(b'BT /F1 18 Tf 70 710 Td (A short line.) Tj ET /Cs1 cs .86 .08 .12 sc 70 590 180 50 re f')
        writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
        with self.source.open('wb') as output: writer.write(output)
        before = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        result = self.export(self.request())
        self.assertNotIn('error', result)
        after = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        # Compare the actual colored drawing, excluding all translated text.
        offset = (792-620)*before['stride'] + 100*4
        self.assertEqual(before['pixels'][offset:offset+4], after['pixels'][offset:offset+4])
        self.assertNotEqual(b'\0\0\0\xff', after['pixels'][offset:offset+4])

    def test_calibrated_stencil_image_color_survives_russian_text_replacement(self):
        calibrated_image_mask_pdf(self.source)
        page = run('page', self.source, 0)
        self.assertEqual(1, len(page['blocks']))
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=page['blocks'])
        request['blocks'][0]['translatedText'] = 'Предупреждение'
        request['blocks'][0]['status'] = 'translated'
        source = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(200, 300))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        output = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(200, 300))
        # The 100x100 pt stencil occupies this visible top-left bitmap rectangle.
        for row in range(140, 240):
            start = row * source['stride'] + 20 * 4
            end = start + 100 * 4
            self.assertEqual(source['pixels'][start:end], output['pixels'][start:end],
                             f'CalRGB stencil color changed at bitmap row {row}')
        pixel = 190 * source['stride'] + 50 * 4
        self.assertNotEqual(b'\0\0\0\xff', source['pixels'][pixel:pixel + 4],
                            'fixture must exercise a non-black CalRGB mask color')

    def test_merge_rejects_tampered_rgb_indexed_palette_and_smask_atomically(self):
        for variant in ('rgb', 'indexed', 'smask'):
            with self.subTest(variant=variant):
                self.source = self.directory / f'{variant}-source.pdf'
                image_integrity_pdf(self.source, variant)
                shards = []
                for index in range(2):
                    blocks = run('page', self.source, index)['blocks']
                    for block in blocks:
                        block['translatedText'] = 'Выполнить обслуживание.'
                        block['status'] = 'translated'
                    snapshot = dict(layoutVersion=3, mode='preserve-source',
                                    sourcePageIndex=index, blocks=blocks)
                    preview = self.directory / f'{variant}-preview-{index}.pdf'
                    exported = run('export', self.source, index,
                                   json.dumps(snapshot, ensure_ascii=False), preview, FONT)
                    self.assertNotIn('error', exported, 'control preview should be valid before tampering')
                    snapshot_path = self.directory / f'{variant}-blocks-{index}.json'
                    snapshot_path.write_text(json.dumps(snapshot, ensure_ascii=False))
                    shards.append(dict(sourcePageIndex=index, expectedPageCount=1,
                                       path=str(preview), blocksPath=str(snapshot_path)))
                mutate_preview_image(shards[0]['path'], variant)
                envelope = dict(layoutVersion=3, mode='preserve-source', sourcePath=str(self.source),
                                fontPath=str(FONT), shards=shards)
                self.output.write_bytes(b'previous verified output')
                merged = run('merge', json.dumps(envelope, ensure_ascii=False), self.output)
                self.assertIn('error', merged, f'{variant} visual tampering must invalidate the preview')
                self.assertEqual(b'previous verified output', self.output.read_bytes(),
                                 'failed merge must preserve the prior verified artifact')
                self.assertFalse(Path(str(self.output) + '.preserving').exists())
                self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_superscript_trademark_joins_its_label_and_retains_its_original_object(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject
        writer = PdfWriter(PdfReader(self.source))
        writer.pages[0]['/Resources']['/Font']['/F1'][NameObject('/Encoding')] = NameObject('/WinAnsiEncoding')
        stream = DecodedStreamObject()
        stream.set_data(b'BT /F1 9 Tf 40 700 Td (IntelliBeam) Tj ET BT /F1 6 Tf 84 704 Td (\xae) Tj ET BT /F1 9 Tf 93 700 Td (System Button \\(If) Tj ET BT /F1 9 Tf 40 690 Td (Equipped\\).) Tj ET')
        writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
        with self.source.open('wb') as output: writer.write(output)
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(1, len(blocks))
        self.assertEqual('®', blocks[0]['symbols'][0]['sourceText'].strip())
        token = blocks[0]['symbols'][0]['token']
        target = f'IntelliBeam{token}: кнопка (при наличии).'
        blocks[0]['translatedText'] = target
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=blocks)
        before = pdfium_snapshot(self.source, dimensions=(612, 792))['objects'][1]
        self.assertNotIn('error', self.export(request))
        after = pdfium_snapshot(self.output, dimensions=(612, 792))['objects']
        self.assertIn(before, after, 'the original superscript glyph/font/matrix bounds remain intact')
        # Legacy checkpoints contain the literal mark without symbol metadata.
        blocks[0].pop('symbols'); blocks[0]['modelText'] = blocks[0]['sourceText']
        blocks[0]['translatedText'] = target.replace(token, '®')
        self.assertNotIn('error', self.export(request))
        blocks[0]['translatedText'] = 'IntelliBeam: кнопка (при наличии).'
        self.assertIn('omitted an original trademark', self.export(request).get('error', ''))

    def test_legacy_checkpoint_restores_multiple_trademarks_in_source_reading_order(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject
        writer = PdfWriter(PdfReader(self.source))
        writer.pages[0]['/Resources']['/Font']['/F1'][NameObject('/Encoding')] = NameObject('/WinAnsiEncoding')
        stream = DecodedStreamObject()
        # Paint the second superscript before the first. Legacy checkpoint
        # restoration must sort symbol definitions by source ownership rather
        # than losing a mark when its visible reading order differs.
        stream.set_data(b'BT /F1 9 Tf 40 700 Td (Alpha) Tj ET '
                        b'BT /F1 6 Tf 94 704 Td (\x99) Tj ET '
                        b'BT /F1 6 Tf 64 704 Td (\xae) Tj ET '
                        b'BT /F1 9 Tf 72 700 Td (Beta) Tj ET')
        writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
        with self.source.open('wb') as output:
            writer.write(output)
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(1, len(blocks))
        block = blocks[0]
        self.assertEqual(2, len(block['symbols']))
        block.pop('symbols')
        block['modelText'] = block['sourceText']
        block['translatedText'] = block['sourceText']
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=blocks)
        before = pdfium_snapshot(self.source, dimensions=(612, 792))['objects']
        result = self.export(request)
        self.assertNotIn('error', result)
        after = pdfium_snapshot(self.output, dimensions=(612, 792))['objects']
        self.assertIn(before[1], after)
        self.assertIn(before[2], after)

    def test_interleaved_source_units_cannot_publish_overlapping_target_text(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject, DictionaryObject
        writer = PdfWriter(PdfReader(self.source))
        writer.pages[0]['/Resources']['/Font'][NameObject('/F2')] = writer._add_object(DictionaryObject({NameObject('/Type'): NameObject('/Font'), NameObject('/Subtype'): NameObject('/Type1'), NameObject('/BaseFont'): NameObject('/Helvetica-Bold')}))
        stream = DecodedStreamObject()
        stream.set_data(b'BT /F1 18 Tf 70 700 Td (Part one) Tj ET BT /F2 18 Tf 180 700 Td (Other label) Tj ET BT /F1 18 Tf 70 678 Td (continued paragraph text) Tj ET')
        writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
        with self.source.open('wb') as output: writer.write(output)
        request = self.request()
        self.assertEqual(2, len(request['blocks']))
        for block in request['blocks']:
            block['translatedText'] = 'Длинная строка перевода' if block['sourceText'].startswith('Part') else 'Метка'
        self.output.write_bytes(b'last verified PDF')
        result = self.export(request)
        self.assertIn('overlaps another source text block', result.get('error', ''))
        self.assertEqual(b'last verified PDF', self.output.read_bytes())

    def test_unsupported_original_type3_icon_fails_closed_instead_of_disappearing(self):
        mixed_type3_inline_pdf(self.source)
        before = pdfium_snapshot(self.source, dimensions=(240, 120))
        request = self.request()
        block = request['blocks'][0]
        token = block['symbols'][0]['token']
        block['translatedText'] = 'Жми ' + token + ' сейчас.'
        self.output.write_bytes(b'previous verified document')
        result = self.export(request)
        self.assertIn('pictogram was lost', result.get('error', ''))
        self.assertEqual(b'previous verified document', self.output.read_bytes())
        self.assertEqual(before['objects'], pdfium_snapshot(self.source, dimensions=(240, 120))['objects'])

    def test_unfit_missing_and_stale_targets_leave_last_output_intact(self):
        for defect in ('unfit', 'missing', 'ownership', 'page'):
            with self.subTest(defect=defect):
                request = self.request()
                if defect == 'unfit': request['blocks'][0]['translatedText'] = 'непомещающийся' * 500
                if defect == 'missing': request['blocks'][0]['translatedText'] = None
                if defect == 'ownership': request['blocks'][0]['sourceText'] = 'wrong source'
                if defect == 'page': request['sourcePageIndex'] = 1
                self.output.write_bytes(b'previous verified output')
                result = self.export(request)
                self.assertIn('error', result)
                if defect == 'unfit':
                    feedback = result['layoutFeedback']
                    self.assertEqual(request['blocks'][0]['id'], feedback['blockId'])
                    self.assertGreater(feedback['availableWidthPt'], 0)
                    self.assertGreater(feedback['availableHeightPt'], 0)
                    self.assertGreaterEqual(feedback['minimumFontSizePt'], 6)
                    self.assertTrue(1 <= feedback['estimatedMaxCharacters'] < len(request['blocks'][0]['translatedText']))
                else:
                    self.assertNotIn('layoutFeedback', result)
                self.assertEqual(b'previous verified output', self.output.read_bytes())
                self.assertFalse(Path(str(self.output) + '.preserving').exists())

    def test_original_source_cannot_be_overwritten_even_through_alias(self):
        alias = self.directory / 'alias.pdf'
        alias.symlink_to(self.source)
        before = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.assertIn('error', self.export(self.request(), output=alias))
        self.assertEqual(before, hashlib.sha256(self.source.read_bytes()).hexdigest())

    def test_staging_alias_and_hardlink_cannot_overwrite_the_original_source(self):
        before = self.source.read_bytes()
        for suffix in ('.preserving', '.preserving.part'):
            alias = Path(str(self.output) + suffix)
            alias.symlink_to(self.source)
            self.assertIn('error', self.export(self.request()))
            self.assertEqual(before, self.source.read_bytes())
            alias.unlink()
        self.output.hardlink_to(self.source)
        self.assertIn('error', self.export(self.request()))
        self.assertEqual(before, self.source.read_bytes())

    def test_full_original_document_retains_named_bookmark_and_cross_page_link_targets(self):
        from pypdf import PdfReader
        navigation_pdf(self.source)
        shards = []
        for index in range(2):
            request = self.request(index)
            preview = self.directory / f'preview-{index}.pdf'
            self.assertNotIn('error', self.export(request, index, preview))
            snapshot = self.directory / f'blocks-{index}.json'
            snapshot.write_text(json.dumps(request, ensure_ascii=False))
            shards.append(dict(sourcePageIndex=index, expectedPageCount=1, path=str(preview), blocksPath=str(snapshot)))
        envelope = dict(layoutVersion=3, mode='preserve-source', sourcePath=str(self.source), fontPath=str(FONT), shards=shards)
        result = run('merge', json.dumps(envelope), self.output)
        self.assertNotIn('error', result)
        self.assertEqual(2, result['pageCount'])
        self.assertEqual([0, 1], [p['outputPageIndex'] for p in result['pages']])
        before, after = PdfReader(self.source), PdfReader(self.output)
        self.assertEqual(1, after.get_destination_page_number(after.named_destinations['chapter-two']))
        self.assertEqual(1, after.get_destination_page_number(after.outline[0]))
        target = after.pages[0]['/Annots'][0].get_object()['/Dest'][0]
        self.assertEqual(after.pages[1].indirect_reference, target)
        for index in range(2):
            self.assertEqual(before.pages[index].mediabox, after.pages[index].mediabox)
            self.assertEqual(before.pages[index].cropbox, after.pages[index].cropbox)
        saved = self.output.read_bytes()
        shards[1]['sourcePageIndex'] = 0
        self.assertIn('error', run('merge', json.dumps(envelope), self.output))
        self.assertEqual(saved, self.output.read_bytes())

    def test_one_source_text_object_splits_at_real_two_and_three_column_rules(self):
        for values in (['Fan', 'Rear'], ['Fan', '15', 'Rear']):
            with self.subTest(columns=len(values)):
                requests = self.table_request(values)
                spans = [block['sourceSpans'][0]['characterIndices'] for block in requests[0]['blocks']]
                self.assertTrue(all(spans))
                expected = [item['index'] for item in pdfium_character_snapshot(self.source) if not item['text'].isspace()]
                self.assertEqual(expected,
                                 sorted(index for span in spans for index in span))

    def test_source_text_targets_preserve_exact_glyph_font_position_art_crop_and_navigation(self):
        from pypdf import PdfReader
        requests = self.table_request(navigation=True)
        original_glyphs = [item for item in pdfium_character_snapshot(self.source) if not item['text'].isspace()]
        self.assertEqual('Fan15Rear', ''.join(item['text'] for item in original_glyphs))
        shard = self.directory / 'preview-0.pdf'
        self.assertNotIn('error', self.export(requests[0], output=shard))
        self.assertFalse(run('export', self.source, 1, json.dumps(requests[1], ensure_ascii=False),
                             self.directory / 'preview-1.pdf', FONT).get('error'))
        shards = []
        for index, request in enumerate(requests):
            snapshot = self.directory / f'blocks-{index}.json'
            snapshot.write_text(json.dumps(request, ensure_ascii=False))
            preview = self.directory / f'preview-{index}.pdf'
            shards.append(dict(sourcePageIndex=index, expectedPageCount=1,
                               path=str(preview), blocksPath=str(snapshot)))
        envelope = dict(layoutVersion=3, mode='preserve-source', sourcePath=str(self.source),
                        fontPath=str(FONT), shards=shards)
        result = run('merge', json.dumps(envelope), self.output)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        output_glyphs = [item for item in pdfium_character_snapshot(self.output) if not item['text'].isspace()]
        self.assertEqual([item['text'] for item in original_glyphs], [item['text'] for item in output_glyphs])
        for original, output in zip(original_glyphs, output_glyphs):
            self.assertEqual(original['font'], output['font'])
            self.assertAlmostEqual(original['font_size'], output['font_size'], places=4)
            for key in ('origin', 'box'):
                self.assertEqual(len(original[key]), len(output[key]))
                for expected, actual in zip(original[key], output[key]):
                    self.assertAlmostEqual(expected, actual, places=3, msg=f'source glyph {key} is unchanged')
            for expected, actual in zip(original['matrix'][:4], output['matrix'][:4]):
                self.assertAlmostEqual(expected, actual, places=5,
                                       msg='the source font glyph keeps its original linear transform')
        before, after = PdfReader(self.source), PdfReader(self.output)
        self.assertEqual(2, len(after.pages))
        self.assertEqual(before.pages[0].mediabox, after.pages[0].mediabox)
        self.assertEqual(before.pages[0].cropbox, after.pages[0].cropbox)
        self.assertEqual(1, after.get_destination_page_number(after.named_destinations['chapter-two']))
        self.assertEqual(1, after.get_destination_page_number(after.outline[0]))
        self.assertEqual(after.pages[1].indirect_reference,
                         after.pages[0]['/Annots'][0].get_object()['/Dest'][0])
        source_art = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(612, 792))
        output_art = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(612, 792))
        self.assertEqual([obj for obj in source_art['objects'] if obj[0] != 1],
                         [obj for obj in output_art['objects'] if obj[0] != 1])
        self.assertEqual(source_art['pixels'], output_art['pixels'],
                         'source==target text and nontext artwork render pixel-identically')

    def test_independent_cell_translation_keeps_adjacent_numeric_cell_exact(self):
        from pypdf import PdfReader
        request = self.table_request()[0]
        request['blocks'][0]['translatedText'] = 'Вентилятор'
        request['blocks'][1]['translatedText'] = '15'
        request['blocks'][2]['translatedText'] = 'Сзади'
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        text = ''.join(PdfReader(self.output).pages[0].extract_text().split())
        self.assertIn('Вентилятор', text)
        self.assertIn('15', text)
        self.assertIn('Сзади', text)
        self.assertNotIn('Fan', text)
        self.assertNotIn('Rear', text)

    def test_malformed_split_character_ownership_rejects_atomically(self):
        def missing_span(request):
            del request['blocks'][1]['sourceSpans']

        def omitted_character(request):
            request['blocks'][0]['sourceSpans'][0]['characterIndices'].pop()

        def duplicate_character(request):
            request['blocks'][0]['sourceSpans'][0]['characterIndices'].append(
                request['blocks'][0]['sourceSpans'][0]['characterIndices'][-1])

        def foreign_object(request):
            request['blocks'][0]['sourceSpans'][0]['objectIndex'] = 999

        def omitted_cell(request):
            request['blocks'].pop(1)

        cases = (missing_span, omitted_character, duplicate_character, foreign_object, omitted_cell)
        for mutate in cases:
            with self.subTest(malformed=mutate.__name__):
                request = self.table_request()[0]
                mutate(request)
                self.output.write_bytes(b'previous verified export')
                result = self.export(request)
                self.assertIn('error', result)
                self.assertEqual(b'previous verified export', self.output.read_bytes())
                self.assertFalse(Path(str(self.output) + '.preserving').exists())
                self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_legacy_whole_object_checkpoint_without_spans_remains_usable(self):
        request = self.table_request()[0]
        glyphs = pdfium_character_snapshot(self.source)
        source_text = ''.join(item['text'] for item in glyphs)
        split = request['blocks']
        legacy = dict(split[0])
        legacy.update(text=source_text, sourceText=source_text, modelText=source_text,
                      translatedText=source_text,
                      left=min(block['left'] for block in split),
                      top=min(block['top'] for block in split),
                      right=max(block['right'] for block in split),
                      bottom=max(block['bottom'] for block in split))
        legacy.pop('sourceSpans', None)
        legacy.pop('sourceRects', None)
        legacy['objectIndices'] = split[0]['objectIndices']
        request['blocks'] = [legacy]
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        output_glyphs = pdfium_character_snapshot(self.output)
        self.assertEqual([item['text'] for item in glyphs], [item['text'] for item in output_glyphs])

    def test_unchanged_kerned_text_object_keeps_character_geometry_after_cell_replacement(self):
        table_cells_pdf(self.source, kerned_header=True)
        page = run('page', self.source, 0)
        header = [item for item in pdfium_character_snapshot(self.source)
                  if not item['text'].isspace() and item['origin'][1] > 700]
        self.assertEqual('FluidUsage', ''.join(item['text'] for item in header))
        self.assertTrue(any('Fluid' in block['sourceText'] and 'Usage' in block['sourceText']
                            for block in page['blocks']))
        for block in page['blocks']:
            block['translatedText'] = block['sourceText']
            block['status'] = 'translated'
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=page['blocks'])
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        output_header = [item for item in pdfium_character_snapshot(self.output)
                         if not item['text'].isspace() and item['origin'][1] > 700]
        self.assertEqual([item['text'] for item in header], [item['text'] for item in output_header])
        for original, output in zip(header, output_header):
            self.assertEqual(original['font'], output['font'])
            self.assertAlmostEqual(original['font_size'], output['font_size'], places=4)
            for key in ('origin', 'box'):
                for expected, actual in zip(original[key], output[key]):
                    self.assertAlmostEqual(expected, actual, places=3,
                                           msg=f'unmodified kerned glyph keeps its {key}')

    def test_clipped_source_text_fails_closed_without_replacing_prior_export(self):
        table_cells_pdf(self.source, clipped_header=True)
        page = run('page', self.source, 0)
        self.assertTrue(any('Clipped' in block['sourceText'] for block in page['blocks']))
        for block in page['blocks']:
            block['translatedText'] = block['sourceText']
            block['status'] = 'translated'
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=page['blocks'])
        self.output.write_bytes(b'previous verified output')
        result = self.export(request)
        self.assertIn('error', result, 'text clipping cannot be silently discarded during source-run reconstruction')
        self.assertEqual(b'previous verified output', self.output.read_bytes())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_multiline_numeric_cell_retains_each_source_glyph_position(self):
        table_cells_pdf(self.source, values=['Fan', '2.0', 'Rear'], multiline_numeric=True)
        page = run('page', self.source, 0)
        numeric = ''.join(block['sourceText'] for block in page['blocks']
                          if any(char in block['sourceText'] for char in '2.0L')).replace(' ', '')
        self.assertEqual('2.0L', numeric)
        source = [item for item in pdfium_character_snapshot(self.source)
                  if not item['text'].isspace() and item['text'] in '2.0L']
        self.assertEqual('2.0L', ''.join(item['text'] for item in source))
        for block in page['blocks']:
            block['translatedText'] = block['sourceText']
            block['status'] = 'translated'
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=page['blocks'])
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        output = [item for item in pdfium_character_snapshot(self.output)
                  if not item['text'].isspace() and item['text'] in '2.0L']
        self.assertEqual([item['text'] for item in source], [item['text'] for item in output])
        for original, exported in zip(source, output):
            self.assertEqual(original['font'], exported['font'])
            self.assertAlmostEqual(original['font_size'], exported['font_size'], places=4)
            for key in ('origin', 'box'):
                for expected, actual in zip(original[key], exported[key]):
                    self.assertAlmostEqual(expected, actual, places=3,
                                           msg=f'multiline numeric glyph keeps its {key}')

    def test_primary_font_covers_russian_without_loading_optional_fallback(self):
        from pypdf import PdfReader
        request = self.request()
        unavailable_fallback = self.directory / 'not-installed-cjk-font.ttf'
        result = self.export(request, cjk_font=unavailable_fallback)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertIn('Коротко.', ''.join(block['text'] for block in run('page', self.output, 0)['blocks']))
        self.assertLess(self.output.stat().st_size, 2 * 1024 * 1024,
                        'a primary-covered target must not embed the 10 MB CJK font')
        fonts = PdfReader(self.output).pages[0]['/Resources']['/Font'].get_object().values()
        base_fonts = [font.get_object().get('/BaseFont', '') for font in fonts]
        self.assertFalse(any('NotoSansSC' in str(name) for name in base_fonts),
                         'the unused fallback must not appear in page resources')

    def test_missing_primary_minus_uses_fallback_and_missing_fallback_rejects_atomically(self):
        request = self.request()
        request['blocks'][0]['translatedText'] = '−'
        result = self.export(request, cjk_font=ROOT / 'assets/fonts/NotoSansSC-Regular.ttf')
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertIn('−', ''.join(block['text'] for block in run('page', self.output, 0)['blocks']))

        self.output.write_bytes(b'previous verified output')
        missing = self.export(request, cjk_font=self.directory / 'missing-sc.ttf')
        self.assertIn('error', missing)
        self.assertEqual(b'previous verified output', self.output.read_bytes())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_fallback_detection_uses_translation_alias_when_primary_lacks_glyph(self):
        request = self.request()
        request['blocks'][0]['translatedText'] = None
        request['blocks'][0]['translation'] = '−'
        result = self.export(request, cjk_font=ROOT / 'assets/fonts/NotoSansSC-Regular.ttf')
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertIn('−', ''.join(block['text'] for block in run('page', self.output, 0)['blocks']))

    def test_late_page_fallback_preserves_full_original_document_navigation(self):
        from pypdf import PdfReader
        navigation_pdf(self.source)
        requests = [self.request(index) for index in range(2)]
        requests[1]['blocks'][0]['translatedText'] = '−'
        shards = []
        cjk_font = ROOT / 'assets/fonts/NotoSansSC-Regular.ttf'
        for index, request in enumerate(requests):
            preview = self.directory / f'lazy-preview-{index}.pdf'
            self.assertNotIn('error', self.export(request, index, preview, cjk_font))
            snapshot = self.directory / f'lazy-blocks-{index}.json'
            snapshot.write_text(json.dumps(request, ensure_ascii=False))
            shards.append(dict(sourcePageIndex=index, expectedPageCount=1,
                               path=str(preview), blocksPath=str(snapshot)))
        envelope = dict(layoutVersion=3, mode='preserve-source', sourcePath=str(self.source),
                        fontPath=str(FONT), cjkFontPath=str(cjk_font), shards=shards)
        merged = run('merge', json.dumps(envelope, ensure_ascii=False), self.output)
        self.assertNotIn('error', merged)
        self.assertEqual(2, merged['pageCount'])
        before, after = PdfReader(self.source), PdfReader(self.output)
        self.assertEqual(2, len(after.pages))
        self.assertEqual(1, after.get_destination_page_number(after.named_destinations['chapter-two']))
        self.assertEqual(1, after.get_destination_page_number(after.outline[0]))
        self.assertEqual(after.pages[1].indirect_reference,
                         after.pages[0]['/Annots'][0].get_object()['/Dest'][0])
        for index in range(2):
            self.assertEqual(before.pages[index].mediabox, after.pages[index].mediabox)
            self.assertEqual(before.pages[index].cropbox, after.pages[index].cropbox)

    def test_read_page_returns_crop_clipped_unclipped_image_artwork_metadata(self):
        from pypdf import PdfReader
        illustration_image_pdf(self.source)
        page = run('page', self.source, 0)
        self.assertEqual([], page['blocks'], 'image pixels alone are not native text translation blocks')
        rects = page['artworkRects']
        self.assertEqual(1, len(rects))
        image = rects[0]
        self.assertEqual('image', image['type'])
        self.assertEqual(2, image['coordinateSpaceVersion'])
        self.assertIsInstance(image['objectIndex'], int)
        for key, expected in {'left': 0, 'top': 70, 'right': 130, 'bottom': 210}.items():
            self.assertAlmostEqual(expected, image[key], places=2)

        payload = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=[])
        exported = run('export', self.source, 0, json.dumps(payload), self.output, FONT)
        self.assertNotIn('error', exported)
        self.assertFalse(exported['partial'])
        self.assertEqual(1, len(PdfReader(self.output).pages))
        source_pixels = pdfium_snapshot(self.source, allow_graphics=True, dimensions=(160, 240))
        output_pixels = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(160, 240))
        self.assertEqual(source_pixels['pixels'], output_pixels['pixels'],
                         'an OCR-filtered illustration remains pixel-identical in source-preserving export')

        illustration_image_pdf(self.source, complex_clip=True)
        clipped = run('page', self.source, 0)
        self.assertEqual([], clipped['artworkRects'],
                         'images with complex clipping are omitted so they cannot suppress unrelated OCR')

    def test_read_page_image_artwork_coordinates_follow_rotated_visible_crop(self):
        illustration_image_pdf(self.source, rotation=90)
        page = run('page', self.source, 0)
        self.assertEqual(1, page['rotation'], 'rotation metadata uses normalized quarter turns')
        image, = page['artworkRects']
        self.assertEqual(2, image['coordinateSpaceVersion'])
        self.assertEqual('image', image['type'])
        for key, expected in {'left': 30, 'top': 0, 'right': 170, 'bottom': 130}.items():
            self.assertAlmostEqual(expected, image[key], places=2)


if __name__ == '__main__':
    unittest.main()

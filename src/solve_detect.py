"""
Detect whether a question paper has been solved (written on), and on which pages.

The student's PDF is rendered page by page and compared with Cambridge's clean
original: anything darker than the original (pen strokes, typed answers, stamps)
is "ink". This works no matter which app was used to write on the paper.
If the clean original can't be found, a weaker fallback looks for coloured ink
and PDF annotations instead.
"""

import pypdfium2 as pdfium
from PIL import Image, ImageChops, ImageFilter

RENDER_SCALE = 1.0          # 72 dpi is plenty to spot handwriting
INK_DARKER_BY = 70          # grey levels a pixel must darken by to count as ink
PAGE_INK_RATIO = 0.0015     # 0.15% of a page darkened = something was written
COLOUR_SATURATION = 90      # fallback: how colourful a pixel must be to count as ink
ANNOTATION_TYPES = {"/Ink", "/FreeText", "/Square", "/Circle", "/Line", "/Polygon", "/PolyLine", "/Stamp", "/Text"}


class SolveResult:
    def __init__(self, solved, pages, method, page_count):
        self.solved = solved            # True if the paper looks written on
        self.pages = pages              # 1-based page numbers with writing
        self.method = method            # "compared" (reliable) or "estimated" (fallback)
        self.page_count = page_count

    def __repr__(self):
        return f"SolveResult(solved={self.solved}, pages={self.pages}, method={self.method!r})"


def render_pages(pdf_path, scale=RENDER_SCALE, mode="L"):
    """Render every page of a PDF to a PIL image."""
    document = pdfium.PdfDocument(pdf_path)
    try:
        images = []
        for index in range(len(document)):
            page = document[index]
            bitmap = page.render(scale=scale, may_draw_forms=True)
            images.append(bitmap.to_pil().convert(mode))
            page.close()
        return images
    finally:
        document.close()


def ink_ratio(student_page, clean_page):
    """Fraction of the page that is darker in the student's copy than in the original."""
    if student_page.size != clean_page.size:
        student_page = student_page.resize(clean_page.size)
    # Let the original's printing spread by a couple of pixels, so a slightly
    # shifted re-save of the same paper doesn't look like writing
    clean_spread = clean_page.filter(ImageFilter.MinFilter(5))
    darker = ImageChops.subtract(clean_spread, student_page)
    ink = darker.point(lambda value: 255 if value >= INK_DARKER_BY else 0)
    histogram = ink.histogram()
    return histogram[255] / (ink.width * ink.height)


def detect(student_pdf, clean_pdf=None):
    """Compare against the clean original if given, otherwise estimate."""
    if clean_pdf:
        return _compare(student_pdf, clean_pdf)
    return _estimate(student_pdf)


def _compare(student_pdf, clean_pdf):
    student = render_pages(student_pdf)
    clean = render_pages(clean_pdf)
    pages = []
    for number, page in enumerate(student, start=1):
        if number > len(clean):
            pages.append(number)  # extra pages added by the student (e.g. working paper)
        elif ink_ratio(page, clean[number - 1]) >= PAGE_INK_RATIO:
            pages.append(number)
    # Writing only a name on the cover doesn't count as solving it
    solved = any(number > 1 for number in pages)
    return SolveResult(solved, pages, "compared", len(student))


def _estimate(student_pdf):
    """No original to compare with: look for coloured ink and PDF annotations."""
    from pypdf import PdfReader

    pages = set()
    reader = PdfReader(student_pdf)
    for number, page in enumerate(reader.pages, start=1):
        for annotation in page.get("/Annots") or []:
            if str(annotation.get_object().get("/Subtype")) in ANNOTATION_TYPES:
                pages.add(number)

    for number, image in enumerate(render_pages(student_pdf, mode="RGB"), start=1):
        saturation = image.convert("HSV").getchannel("S")
        coloured = saturation.point(lambda value: 255 if value >= COLOUR_SATURATION else 0).histogram()[255]
        if coloured / (image.width * image.height) >= PAGE_INK_RATIO:
            pages.add(number)

    pages = sorted(pages)
    return SolveResult(any(n > 1 for n in pages), pages, "estimated", len(reader.pages))

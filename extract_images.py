"""Extract images from PDF files in the pdfs folder."""
import os
import fitz  # pymupdf

PDF_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pdfs")
OUT_DIR = os.path.join(PDF_DIR, "extracted_images")
os.makedirs(OUT_DIR, exist_ok=True)

for pdf_name in sorted(os.listdir(PDF_DIR)):
    if not pdf_name.lower().endswith(".pdf"):
        continue
    pdf_path = os.path.join(PDF_DIR, pdf_name)
    doc = fitz.open(pdf_path)
    stem = os.path.splitext(pdf_name)[0].replace(" ", "_")
    count = 0
    for page_num, page in enumerate(doc):
        images = page.get_images(full=True)
        for img_index, img in enumerate(images):
            xref = img[0]
            pix = fitz.Pixmap(doc, xref)
            if pix.n - pix.alpha > 3:  # CMYK -> RGB
                pix = fitz.Pixmap(fitz.csRGB, pix)
            out_name = f"{stem}_p{page_num + 1}_i{img_index + 1}.png"
            out_path = os.path.join(OUT_DIR, out_name)
            pix.save(out_path)
            count += 1
            print(f"{pdf_name} -> {out_name} ({pix.width}x{pix.height})")
            pix = None
    print(f"--- {pdf_name}: {count} images, {len(doc)} pages ---")
    doc.close()
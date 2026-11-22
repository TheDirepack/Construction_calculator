import fitz, json, sys

PDF = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf'
doc = fitz.open(PDF)
print('Pages:', doc.page_count)
print('Metadata:', doc.metadata)
for i in range(min(doc.page_count, 5)):
    page = doc[i]
    print(f"\n=== PAGE {i+1} size={page.rect} ===")
    text = page.get_text()
    print(text[:1500])

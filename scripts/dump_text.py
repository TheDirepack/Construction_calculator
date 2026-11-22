import fitz, os

PDF = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf'
OUT = '/tmp/opencode/wash_text'
os.makedirs(OUT, exist_ok=True)
doc = fitz.open(PDF)
for i in range(doc.page_count):
    page = doc[i]
    text = page.get_text('text')
    with open(f'{OUT}/page_{i+1:03d}.txt','w') as f:
        f.write(text)
print('done', doc.page_count, 'pages')

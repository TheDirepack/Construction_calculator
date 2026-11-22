import fitz, json, os
PDF='/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf'
OUT='/tmp/opencode/wash_textpos'
os.makedirs(OUT, exist_ok=True)
doc=fitz.open(PDF)
for i in range(doc.page_count):
    page=doc[i]
    H=page.rect.height
    blocks=[]
    for b in page.get_text('dict')['blocks']:
        if b['type']!=0: continue
        for line in b['lines']:
            for span in line['spans']:
                txt=span['text']
                if not txt.strip(): continue
                x0,y0,x1,y1=span['bbox']
                blocks.append({'t':txt,'x0':round(x0,2),'y0':round(y0,2),'x1':round(x1,2),'y1':round(y1,2),
                               's':round(span['size'],2),'y0b':round(H-y1,2),'y1b':round(H-y0,2)})
    with open(f'{OUT}/p{i+1:03d}.json','w') as f:
        json.dump({'page':i+1,'w':round(page.rect.width,2),'h':round(page.rect.height,2),'blocks':blocks},f)
print('done', doc.page_count)

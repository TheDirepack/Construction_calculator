import fitz, json, os, sys
PDF='/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf'
OUT='/tmp/opencode/wash_drawings'
os.makedirs(OUT, exist_ok=True)
pno=int(sys.argv[1])
doc=fitz.open(PDF)
page=doc[pno-1]
H=page.rect.height
draws=[]
for dr in page.get_drawings():
    rect=dr['rect']
    x0,y0,x1,y1=rect
    draws.append({
        'type':dr.get('type'), 'fill':dr.get('fill'), 'color':dr.get('color'),
        'width':dr.get('width'), 'items':len(dr.get('items',[])),
        'x0':round(x0,2),'y0':round(y0,2),'x1':round(x1,2),'y1':round(y1,2),
        'y0b':round(H-y1,2),'y1b':round(H-y0,2),
        'w':round(x1-x0,2),'h':round(y1-y0,2)
    })
with open(f'{OUT}/p{pno:03d}.json','w') as f:
    json.dump({'page':pno,'w':round(page.rect.width,2),'h':round(page.rect.height,2),'drawings':draws},f)
print('page',pno,'drawings',len(draws))

import fitz, json, sys
PDF='/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf'
doc=fitz.open(PDF)
pno=int(sys.argv[1])
page=doc[pno-1]
d=page.get_text('dict')
blocks=[]
for b in d['blocks']:
    if b['type']!=0: continue
    for line in b['lines']:
        for span in line['spans']:
            txt=span['text'].strip()
            if not txt: continue
            x0,y0,x1,y1=span['bbox']
            blocks.append({'text':txt,'x0':round(x0,1),'y0':round(y0,1),'x1':round(x1,1),'y1':round(y1,1),'size':round(span['size'],1)})
# convert y to bottom-left origin: page height 2160, top=y0 in fitz is top-left
H=page.rect.height
for b in blocks:
    b['y0_bl']=round(H-b['y1'],1); b['y1_bl']=round(H-b['y0'],1)
print(json.dumps({'page':pno,'rect':[page.rect.width,page.rect.height],'blocks':blocks},indent=1))

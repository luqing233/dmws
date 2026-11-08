"""Analytical balance estimate from the bundled catalog; excludes medical bills and downtime.
Uses mean size factor 1 and a strength-50 player without combat boosts, after newcomer protection.
"""
import json,math
from pathlib import Path

BAITS=[('白',10,0,1,0),('绿',35,.02,1.1,.1),('蓝',75,.04,1.25,.2),('紫',150,.06,1.4,.3),('金',250,.08,1.6,.4),('红',400,.10,1.8,0)]
def estimate():
    fish=json.loads((Path(__file__).resolve().parents[1]/'src/main/resources/economy/fish-catalog.json').read_text(encoding='utf-8'))
    croc=sum(max(.1,min(.9,.5+(50-strength)*.005))*(800+30*(strength-60)) for strength in range(60,101))/41
    fines=sum(w*loss for w,loss in [(120,10),(80,30),(60,50),(30,80),(10,120)])
    rows=[]
    for level in [1,10,25,50,75,100]:
        pool=[f for f in fish if f['level']<=level+1]
        base=lambda f:1 if f['special'] else max(1,120-f['difficulty'])
        for name,price,bonus,high,risk in BAITS:
            growth=((level-1)/99)**.8
            specials=sum(f['special'] for f in pool)
            mass=specials/sum(base(f) for f in pool)
            if name=='红':mass=min(.1,mass*3)
            weight=lambda f:base(f)*(1+2*growth*max(0,min(1,(f['level']-1)/9))**1.5)*(high if f['level']>=7 else 1)
            total=sum(weight(f) for f in pool if not f['special'])
            sale=0
            success_probability=0
            for f in pool:
                chance=max(.25,min(1,.82-f['difficulty']/160-max(0,f['level']-level)*.15))
                chance=min(.90,chance+min(.15,.30*growth*(1-chance))+bonus)
                probability=mass/specials if f['special'] else (1-mass)*weight(f)/total
                success_probability+=probability*chance
                sale+=probability*chance*f['price']*(1+.02*(level-1))*.5
            if name=='红':
                gross=.8*sale+.04*croc+.08*(.5*40+.3*70+.2*20)+.06*(.6*200+.3*500+.1*1400)-.02*fines/320
            else:gross=(1-.04*(1-risk))*sale+(1-risk)*(.008*croc-fines/10000)
            rows.append(dict(level=level,bait=name,normal_success_pct=round(success_probability*100,2),mean_coin_change=round(gross,2),bait_cost=price/5,net_before_medical=round(gross-price/5,2)))
    return rows
if __name__=='__main__':print(json.dumps(estimate(),ensure_ascii=False,indent=2))

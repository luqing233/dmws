"""Analytical balance estimate from the bundled catalog; excludes medical bills and downtime.
Uses mean size factor 1 and a strength-50 player without combat boosts.
"""
import json,math
from pathlib import Path

RESOURCES = Path(__file__).resolve().parents[1] / "src/main/resources/economy"
BAITS = dict(zip(("WHITE", "GREEN", "BLUE", "PURPLE", "GOLD", "RED"), zip("白绿蓝紫金红", (10, 35, 75, 150, 250, 400))))


def event_contribution(rule, events):
    event_rate = value = 0
    for pool in rule["pools"]:
        entries = [event for event in events if event["pool_code"] == pool["code"]]
        total = sum(event["tickets"] for event in entries)
        for event in entries:
            if "budget" in pool:
                rate = pool["budget"] * event["tickets"] / total / 10000 if total else 0
            else:
                rate = math.floor(event["tickets"] * pool["scale_bps"] / 10000 + .5) / 10000
            kind = event["kind"]
            event_rate += rate
            if kind == "MONEY":
                value += rate * (event["amount_min"] + event["amount_max"]) / 2
            elif kind == "COMBAT":
                low, high = event.get("strength_min", 60), event.get("strength_max", 100)
                reward_min, reward_max = event["amount_min"], event["amount_max"]
                winnings = sum(max(.1, min(.9, .5 + (50 - strength) * .005))
                               * (reward_min + (reward_max - reward_min)
                                  * ((strength - low) / (high - low) if high > low else 1))
                               for strength in range(low, high + 1)) / (high - low + 1)
                value += rate * winnings
    return event_rate, value


def estimate():
    fish = json.loads((RESOURCES / "fish-catalog.json").read_text(encoding="utf-8"))
    events = json.loads((RESOURCES / "fishing-events.json").read_text(encoding="utf-8"))
    rules = json.loads((RESOURCES / "fishing-bait-rules.json").read_text(encoding="utf-8"))
    rows=[]
    for level in [1,10,25,50,75,100]:
        pool=[f for f in fish if f['level']<=level+1]
        base=lambda f:1 if f['special'] else max(1,120-f['difficulty'])
        for rule in rules:
            name, price = BAITS[rule["quality"]]
            bonus = rule["success_bonus_bps"] / 10000
            high = rule["high_fish_weight_bps"] / 10000
            growth=((level-1)/99)**.8
            specials=sum(f['special'] for f in pool)
            mass=specials/sum(base(f) for f in pool)
            mass = min(rule["special_cap_bps"] / 10000, mass * rule["special_multiplier_bps"] / 10000)
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
            event_rate, event_value = event_contribution(rule, events)
            gross = (1 - event_rate) * sale + event_value
            rows.append(dict(level=level,bait=name,normal_success_pct=round(success_probability*100,2),mean_coin_change=round(gross,2),bait_cost=price/5,net_before_medical=round(gross-price/5,2)))
    return rows
if __name__=='__main__':print(json.dumps(estimate(),ensure_ascii=False,indent=2))

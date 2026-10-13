import json, time, urllib.request, urllib.parse
UA = {"User-Agent": "MinisResearch/0.1 (nazhenhuiyi@gmail.com)"}
P = [("Anhui","安徽"),("Beijing","北京"),("Chongqing","重庆"),("Fujian","福建"),("Gansu","甘肃"),("Guangdong","广东"),("Guangxi","广西"),("Guizhou","贵州"),
     ("Hainan","海南"),("Hebei","河北"),("Heilongjiang","黑龙江"),("Henan","河南"),("Hong Kong","香港"),("Hubei","湖北"),("Hunan","湖南"),("Nei Mongol","内蒙古"),
     ("Jiangsu","江苏"),("Jiangxi","江西"),("Jilin","吉林"),("Liaoning","辽宁"),("Macau","澳门"),("Ningxia","宁夏"),("Qinghai","青海"),("Shaanxi","陕西"),
     ("Shandong","山东"),("Shanghai","上海"),("Shanxi","山西"),("Sichuan","四川"),("Taiwan","台湾"),("Tianjin","天津"),("Xinjiang","新疆"),("Xizang","西藏"),
     ("Yunnan","云南"),("Zhejiang","浙江")]
def get(url):
    for a in range(6):
        try: return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30))
        except Exception as e: print('retry', e, flush=True); time.sleep(5 + 5 * a)
    raise SystemExit('network')
out = []
for en, zh in P:
    r = get("https://api.inaturalist.org/v1/places/autocomplete?per_page=10&q=" + urllib.parse.quote(en))
    hit = next((p for p in r["results"] if p.get("admin_level") in (0, 10) and p["name"].lower().startswith(en.lower().split()[0])
                and (6903 in (p.get("ancestor_place_ids") or []) or en in ("Taiwan", "Hong Kong", "Macau"))), None)
    out.append((hit["id"] if hit else None, en, zh)); print(en, hit and (hit["id"], hit["name"], hit.get("admin_level")), flush=True)
    time.sleep(1.1)
json.dump(out, open('/tmp/bc-data/provinces.json', 'w'), ensure_ascii=False)

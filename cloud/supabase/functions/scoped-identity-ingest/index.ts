import { createClient } from "npm:@supabase/supabase-js@2";

const UA = "MoqarinAlasaarScoped/1.0 (+LuLu and Tamimi public price refresh)";
const enc = new TextEncoder();
const MAX_PAGES = 24;

type Retailer = "lulu" | "tamimi";
type Identity = { key:string; variant:string|null; sizeValue:number; sizeUnit:string; packCount:number };
type Parsed = Identity & {
  nameAr:string|null; nameEn:string|null; brand:string; image:string|null;
  price:number; currency:string; sourceUrl:string; parser:string;
};

const STOP = new Set(["can","cans","bottle","bottles","pet","pack","packs","piece","pieces","pc","pcs","ml","ltr","liter","litre","g","gm","gram","kg","each","علبة","علب","عبوة","عبوات","زجاجة","زجاجات","حبة","حبات","مل","لتر","جرام","غرام","كيلو","كجم"]);
const VAR:Record<string,string[]> = {
  zero:["zero","زيرو","صفر"], diet:["diet","دايت","حمية"], light:["light","لايت","خفيف"],
  original:["original","regular","classic","اصلي","أصلي","عادي","كلاسيك"],
  cherry:["cherry","كرز"], vanilla:["vanilla","فانيلا"], lemon:["lemon","ليمون"], lime:["lime","لايم"]
};
const SIZES:Array<[RegExp,string,number]> = [
  [/\b(\d+(?:[.,]\d+)?)\s*(?:ml|مل|مليلتر|millilit(?:er|re)s?)\b/i,"ml",1],
  [/\b(\d+(?:[.,]\d+)?)\s*(?:l|ltr|liter|litre|لتر)\b/i,"ml",1000],
  [/\b(\d+(?:[.,]\d+)?)\s*(?:g|gm|gram|جرام|غرام)\b/i,"g",1],
  [/\b(\d+(?:[.,]\d+)?)\s*(?:kg|kilogram|كيلو|كجم)\b/i,"g",1000]
];
const PACKS = [
  /(?:^|\D)(\d{1,3})\s*[x×]\s*/i,
  /(?:pack|pk|عبوة|عبوات|حبة|حبات)\s*(?:of\s*)?(\d{1,3})(?!\d)/i,
  /(?:^|\D)(\d{1,3})\s*(?:pack|pk|pcs|pieces|حبة|حبات|عبوة|عبوات)(?!\w)/i
];

function secretKey(){
  const modern=Deno.env.get("SUPABASE_SECRET_KEYS");
  if(modern){ try { const parsed=JSON.parse(modern); if(parsed?.default) return String(parsed.default); } catch {} }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
}
async function sha(v:string){
  const h=await crypto.subtle.digest("SHA-256",enc.encode(v));
  return [...new Uint8Array(h)].map(x=>x.toString(16).padStart(2,"0")).join("");
}
function host(v:string){ try { return new URL(v).hostname.toLowerCase().replace(/^www\./,""); } catch { return ""; } }
async function fetchText(url:string, accept="text/html,application/xhtml+xml,*/*;q=0.8"){
  try {
    const r=await fetch(url,{headers:{"User-Agent":UA,Accept:accept,"Accept-Language":"ar-SA,ar;q=0.9,en;q=0.8"},redirect:"follow",signal:AbortSignal.timeout(12000)});
    return r.ok ? await r.text() : null;
  } catch { return null; }
}
function decode(s:string){
  return s.replace(/&nbsp;|&#160;/gi," ").replace(/&amp;/gi,"&").replace(/&quot;/gi,'"')
    .replace(/&#39;|&apos;/gi,"'").replace(/&lt;/gi,"<").replace(/&gt;/gi,">")
    .replace(/&#(\d+);/g,(_,n)=>String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi,(_,n)=>String.fromCodePoint(parseInt(n,16)));
}
function plain(h:string){
  return decode(h.replace(/<script\b[\s\S]*?<\/script>/gi," ").replace(/<style\b[\s\S]*?<\/style>/gi," ").replace(/<[^>]+>/g," ")).replace(/\s+/g," ").trim();
}
function meta(h:string,key:string){
  const e=key.replace(/[.*+?^${}()|[\]\\]/g,"\\$&");
  for(const re of [
    new RegExp(`<meta\\b[^>]*(?:property|name|itemprop)=["']${e}["'][^>]*content=["']([^"']+)["'][^>]*>`,`i`),
    new RegExp(`<meta\\b[^>]*content=["']([^"']+)["'][^>]*(?:property|name|itemprop)=["']${e}["'][^>]*>`,`i`)
  ]){ const m=h.match(re); if(m) return decode(m[1].trim()); }
  return null;
}
function norm(s:string|null){
  return (s??"").normalize("NFKC").toLowerCase().trim().replace(/[أإآ]/g,"ا").replace(/ة/g,"ه").replace(/ى/g,"ي")
    .replace(/[^0-9a-z\u0600-\u06ff]+/gi," ").replace(/\s+/g," ").trim();
}
function numberPrice(v:unknown){
  if(v==null) return null;
  const m=String(v).trim().replace(/٬/g,"").replace(/,/g,"").replace(/٫/g,".").match(/(?:^|\D)(\d{1,7}(?:\.\d{1,2})?)(?:\D|$)/);
  if(!m) return null;
  const n=Number(m[1]);
  return Number.isFinite(n)&&n>0&&n<=100000 ? Math.round(n*100)/100 : null;
}
async function identity(name:string, brand:string, extra=""):Promise<Identity|null>{
  const source=`${name} ${extra} ${brand}`.trim(), nb=norm(brand);
  let sv:number|null=null, su:string|null=null;
  for(const [p,u,m] of SIZES){ const x=source.match(p); if(x){ const n=Number(x[1].replace(",","."))*m; if(n>0&&n<=100000){sv=Math.round(n*1000)/1000;su=u;break;} } }
  let pc=1;
  for(const p of PACKS){ const x=source.match(p); if(x){ const n=Number(x[1]); if(n>=1&&n<=100){pc=n;break;} } }
  const toks=new Set(norm(source).split(" ").filter(Boolean)), vs:string[]=[];
  for(const [k,words] of Object.entries(VAR)) if(words.some(w=>toks.has(norm(w)))) vs.push(k);
  const variant=vs.length?[...new Set(vs)].sort().join("+"):null;
  if(!nb||sv===null||!su) return null;
  let stripped=`${name} ${extra}`;
  for(const [p] of SIZES) stripped=stripped.replace(p," ");
  for(const p of PACKS) stripped=stripped.replace(p," ");
  const brandTokens=new Set(nb.split(" ")), variantTokens=new Set(Object.values(VAR).flat().map(norm));
  const family=[...new Set(norm(stripped).split(" ").filter(t=>t.length>1&&!/^\d+$/.test(t)&&!STOP.has(t)&&!brandTokens.has(t)&&!variantTokens.has(t)))].sort().slice(0,6);
  if(!family.length&&!variant) return null;
  const raw=[nb,variant??"standard",`${String(sv)}:${su}`,String(pc),family.join(",")].join("|");
  return {key:`v2:${(await sha(raw)).slice(0,32)}`,variant,sizeValue:sv,sizeUnit:su,packCount:pc};
}

function robotsRules(txt:string){
  const allow:string[]=[],deny:string[]=[]; let active=false;
  for(const raw of txt.split(/\r?\n/)){
    const line=raw.split("#",1)[0].trim(); if(!line) continue;
    const i=line.indexOf(":"); if(i<0) continue;
    const k=line.slice(0,i).trim().toLowerCase(), v=line.slice(i+1).trim();
    if(k==="user-agent"){ const a=v.toLowerCase(); active=a==="*"||UA.toLowerCase().startsWith(a); continue; }
    if(!active) continue; if(k==="allow"&&v) allow.push(v); if(k==="disallow"&&v) deny.push(v);
  }
  return {allow,deny};
}
function robotsAllows(url:string,r:{allow:string[];deny:string[]}){
  let p="/"; try { const u=new URL(url); p=u.pathname+u.search; } catch { return false; }
  const match=(q:string)=>{ if(!q) return false; const e="^"+q.replace(/[.+?^${}()|[\]\\]/g,"\\$&").replace(/\*/g,".*").replace(/\$$/,"$"); try{return new RegExp(e).test(p);}catch{return p.startsWith(q);} };
  const a=r.allow.filter(match).sort((x,y)=>y.length-x.length)[0]??"", d=r.deny.filter(match).sort((x,y)=>y.length-x.length)[0]??"";
  return !d||a.length>=d.length;
}

function luluUrl(raw:string){
  try { const u=new URL(raw,"https://gcc.luluhypermarket.com/en-sa/"); u.hash=""; if(host(u.toString())!=="gcc.luluhypermarket.com"||!/^\/en-sa\/.+\/p\//i.test(u.pathname)) return null; return u.toString(); } catch { return null; }
}
function tamimiUrl(raw:string){
  try {
    const u=new URL(raw,"https://shop.tamimimarkets.com/en/"); u.hash=""; u.search="";
    if(host(u.toString())!=="shop.tamimimarkets.com") return null;
    if(/^\/product\//i.test(u.pathname)) u.pathname=`/en${u.pathname}`;
    if(!/^\/en\/product\//i.test(u.pathname)) return null;
    return u.toString().replace(/\/$/,"");
  } catch { return null; }
}
function hrefs(h:string){ return [...h.matchAll(/href\s*=\s*["']([^"']+)["']/gi)].map(m=>decode(m[1])); }
function xmlLocs(x:string){ return [...x.matchAll(/<loc>([\s\S]*?)<\/loc>/gi)].map(m=>decode(m[1].trim())).filter(Boolean); }

async function discoverLulu(){
  const queue=["https://gcc.luluhypermarket.com/en-sa/sitemap.xml"], seen=new Set<string>(), products:string[]=[]; let docs=0;
  while(queue.length&&docs<14&&products.length<5000){
    queue.sort((a,b)=>(/product|catalog|sku/i.test(b)?1:0)-(/product|catalog|sku/i.test(a)?1:0));
    const sm=queue.shift()!; if(seen.has(sm)) continue; seen.add(sm);
    const x=await fetchText(sm,"application/xml,text/xml,*/*;q=0.8"); if(!x||(!/<urlset/i.test(x)&&!/<sitemapindex/i.test(x))) continue; docs++;
    const locs=xmlLocs(x);
    if(/<sitemapindex/i.test(x)){ for(const loc of locs) if(host(loc)==="gcc.luluhypermarket.com"&&!seen.has(loc)) queue.push(loc); }
    else for(const loc of locs){ const u=luluUrl(loc); if(u&&!products.includes(u)) products.push(u); }
  }
  return products;
}
async function discoverTamimi(){
  const seeds=[
    "https://shop.tamimimarkets.com/en/category/tamimi-product",
    "https://shop.tamimimarkets.com/en/category/spreads",
    "https://shop.tamimimarkets.com/en/category/spices",
    "https://shop.tamimimarkets.com/en/brand/tamimi-1"
  ];
  const products:string[]=[];
  for(const seed of seeds){ const h=await fetchText(seed); if(!h) continue; for(const raw of hrefs(h)){ const u=tamimiUrl(raw); if(u&&!products.includes(u)) products.push(u); } }
  return products;
}

async function parseLulu(h:string,u:string):Promise<Parsed|null>{
  const hm=h.match(/<h1\b[^>]*>([\s\S]{1,1200}?)<\/h1>/i); if(!hm||hm.index==null) return null;
  const name=plain(hm[1]); if(!name||name.length>240) return null;
  const before=h.slice(Math.max(0,hm.index-5500),hm.index);
  let brand:string|null=null;
  const bm=before.match(/View\s*all\s*products\s*from[\s\S]{0,1400}?<a\b[^>]*>([\s\S]{1,300}?)<\/a>/i); if(bm) brand=plain(bm[1]);
  if(!brand){ const m=plain(before).match(/View\s*all\s*products\s*from\s+([A-Za-z0-9&'’._\- ]{2,80})\s*$/i); if(m) brand=m[1].trim(); }
  if(!brand) return null;
  let amount=numberPrice(meta(h,"product:price:amount")??meta(h,"price")??meta(h,"og:price:amount"));
  if(amount===null){
    const end=hm.index+hm[0].length; let after=h.slice(end,end+6500);
    const marker=after.search(/Selected\s*Pack\s*Quantity|Product\s*Summary|Product\s*Information/i); if(marker>=0) after=after.slice(0,marker);
    const m=plain(after).match(/(?:^|\s)(\d{1,5}(?:[.,]\d{1,2})?)(?=\s|$)/); if(m) amount=numberPrice(m[1]);
  }
  if(amount===null) return null;
  const id=await identity(name,brand); if(!id) return null;
  const image=meta(h,"og:image");
  return {nameAr:/[\u0600-\u06ff]/.test(name)?name:null,nameEn:/[\u0600-\u06ff]/.test(name)?null:name,brand,image,price:amount,currency:(meta(h,"product:price:currency")??"SAR").toUpperCase(),sourceUrl:u,parser:"lulu_h1_brand_price",...id};
}
async function parseTamimi(h:string,u:string):Promise<Parsed|null>{
  const all=plain(h), title=meta(h,"og:title")?.replace(/\s*\|\s*Tamimi Markets.*$/i,"").trim()??null;
  const info=all.match(/\bBrand\s+(.+?)\s+Size\s+(.+?)\s+Price\s+([^\s]+)\s+Additional Information\b/i);
  if(!title||!info) return null;
  const brand=info[1].trim(), size=info[2].trim(), priceText=info[3].trim();
  const brandPos=all.search(/\bBrand\b/i), pre=brandPos>=0?all.slice(Math.max(0,brandPos-600),brandPos):"";
  if(/\bSold Out\b/i.test(pre)||priceText==="-") return null;
  const amount=numberPrice(priceText); if(amount===null) return null;
  const id=await identity(title,brand,size); if(!id) return null;
  return {nameAr:null,nameEn:`${title}-${size}`,brand,image:meta(h,"og:image"),price:amount,currency:"SAR",sourceUrl:u,parser:"tamimi_brand_size_price",...id};
}

function circular<T>(items:T[],start:number,count:number){ const out:T[]=[]; if(!items.length||count<=0) return out; for(let i=0;i<Math.min(count,items.length);i++) out.push(items[(start+i)%items.length]); return out; }
const sleep=(ms:number)=>new Promise(r=>setTimeout(r,ms));

Deno.serve(async(req:Request)=>{
  const started=Date.now();
  const projectUrl=Deno.env.get("SUPABASE_URL")??"", key=secretKey();
  if(!projectUrl||!key) return Response.json({error:"server_config_missing"},{status:503});
  const db=createClient(projectUrl,key,{auth:{persistSession:false,autoRefreshToken:false}});
  const token=req.headers.get("x-ingest-token")??"";
  const {data:ctl}=await db.from("ingest_control").select("value").eq("key","cron_token_sha256").maybeSingle();
  if(!ctl?.value||(await sha(token))!==ctl.value) return Response.json({error:"unauthorized"},{status:401});
  let body:any={}; try{body=await req.json();}catch{}
  const retailer=String(body.retailer??"") as Retailer;
  if(retailer!=="lulu"&&retailer!=="tamimi") return Response.json({error:"unsupported_retailer"},{status:400});
  const max=Math.max(4,Math.min(Number(body.max_pages??20)||20,MAX_PAGES));
  const base=retailer==="lulu"?"https://gcc.luluhypermarket.com/":"https://shop.tamimimarkets.com/";
  const robotText=await fetchText(new URL("/robots.txt",base).toString(),"text/plain,*/*;q=0.8");
  if(!robotText) return Response.json({retailer,status:"robots_unavailable",recorded:0});
  const rr=robotsRules(robotText);
  const {data:r}=await db.from("retailers").select("id").eq("slug",retailer).eq("active",true).maybeSingle();
  if(!r) return Response.json({error:"retailer_inactive",retailer},{status:409});

  const refreshSlots=Math.min(4,Math.max(1,Math.floor(max/4)));
  const {data:knownRows}=await db.from("retailer_identity_sources").select("id,identity_key,source_url,branch_key,last_checked_at")
    .eq("retailer_id",r.id).eq("active",true).order("last_checked_at",{ascending:true,nullsFirst:true}).limit(refreshSlots);
  const knownSet=new Set((knownRows??[]).map((x:any)=>String(x.source_url)));
  const discovered=retailer==="lulu"?await discoverLulu():await discoverTamimi();
  const pool=discovered.filter(u=>!knownSet.has(u));
  const {data:cursorRow}=await db.from("retailer_scan_cursors").select("cursor_position").eq("retailer_id",r.id).eq("stream","identity").maybeSingle();
  const cursor=Number(cursorRow?.cursor_position??0), scanSlots=Math.max(1,max-(knownRows?.length??0));
  const batch=circular(pool,pool.length?cursor%pool.length:0,scanSlots);

  let recorded=0,refreshed=0,rejected=0,failed=0;
  const accepted:any[]=[];
  async function processOne(url:string, expected:string|null, sourceId:number|null, branch="online"){
    if(!robotsAllows(url,rr)){rejected++;return;}
    const h=await fetchText(url); if(!h){failed++; if(sourceId) await db.from("retailer_identity_sources").update({last_checked_at:new Date().toISOString(),last_error:"fetch_failed"}).eq("id",sourceId); return;}
    const p=retailer==="lulu"?await parseLulu(h,url):await parseTamimi(h,url);
    if(!p||(expected&&p.key!==expected)){
      rejected++; if(sourceId) await db.from("retailer_identity_sources").update({last_checked_at:new Date().toISOString(),last_error:"identity_or_price_not_verified"}).eq("id",sourceId); return;
    }
    const {error}=await db.rpc("record_identity_price",{
      p_identity_key:p.key,p_name_ar:p.nameAr,p_name_en:p.nameEn,p_brand:p.brand,p_variant:p.variant,
      p_net_content_value:p.sizeValue,p_net_content_unit:p.sizeUnit,p_pack_count:p.packCount,p_image_url:p.image,
      p_retailer_slug:retailer,p_branch_key:branch,p_price:p.price,p_currency:p.currency,p_source_url:p.sourceUrl
    });
    if(error){failed++;return;}
    accepted.push({name:p.nameAr??p.nameEn,brand:p.brand,price:p.price,url:p.sourceUrl,parser:p.parser});
    if(sourceId){await db.from("retailer_identity_sources").update({last_checked_at:new Date().toISOString(),last_error:null}).eq("id",sourceId);refreshed++;} else recorded++;
  }

  for(const s of knownRows??[]){ if(Date.now()-started>112000) break; await processOne(String(s.source_url),String(s.identity_key),Number(s.id),String(s.branch_key??"online")); await sleep(750); }
  for(const u of batch){ if(Date.now()-started>112000) break; await processOne(u,null,null); await sleep(750); }

  const next=pool.length?(cursor+batch.length)%pool.length:0;
  await db.from("retailer_scan_cursors").upsert({retailer_id:r.id,stream:"identity",cursor_position:next,catalog_size:pool.length,updated_at:new Date().toISOString()},{onConflict:"retailer_id,stream"});
  const {data:changed,error:rebuildError}=await db.rpc("rebuild_product_snapshots");
  return Response.json({retailer,recorded,refreshed,rejected,failed,changed_snapshots:rebuildError?null:changed,accepted:accepted.slice(0,10),catalog_candidates:discovered.length,scan_pool:pool.length,cursor_before:cursor,cursor_after:next,scanned_new:batch.length,elapsed_ms:Date.now()-started});
});

import { createClient } from "npm:@supabase/supabase-js@2";

const UA = "MoqarinAlasaarLuLuList/1.1 (+verified public listed-product refresh)";
const BASE = "https://gcc.luluhypermarket.com";
const LIST = `${BASE}/en-sa/list/`;
const enc = new TextEncoder();

const STOP = new Set(["can","cans","bottle","bottles","pet","pack","packs","piece","pieces","pc","pcs","ml","ltr","liter","litre","g","gm","gram","kg","each","علبة","علب","عبوة","عبوات","زجاجة","زجاجات","حبة","حبات","مل","لتر","جرام","غرام","كيلو","كجم"]);
const VAR: Record<string,string[]> = {
  zero:["zero","زيرو","صفر"], diet:["diet","دايت","حمية"], light:["light","لايت","خفيف"],
  original:["original","regular","classic","اصلي","أصلي","عادي","كلاسيك"], cherry:["cherry","كرز"],
  vanilla:["vanilla","فانيلا"], lemon:["lemon","ليمون"], lime:["lime","لايم"]
};
const SIZES: Array<[RegExp,string,number]> = [
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
const ELECTRONICS = /\b(?:smartphone|iphone|ipad|tablet|laptop|notebook|mobile\s+phone|[245]g\s+(?:smartphone|phone|mobile)|gb\s+storage)\b/i;

type Identity = { key:string; variant:string|null; sv:number; su:string; pc:number };

function secret(){
  const modern=Deno.env.get("SUPABASE_SECRET_KEYS");
  if(modern){ try { const parsed=JSON.parse(modern); if(parsed?.default) return String(parsed.default); } catch {} }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
}
async function sha(value:string){
  const h=await crypto.subtle.digest("SHA-256",enc.encode(value));
  return [...new Uint8Array(h)].map(x=>x.toString(16).padStart(2,"0")).join("");
}
async function fetchText(url:string, accept="text/html,application/xhtml+xml,*/*;q=0.8"){
  try {
    const r=await fetch(url,{headers:{"User-Agent":UA,Accept:accept,"Accept-Language":"ar-SA,ar;q=0.9,en;q=0.8"},redirect:"follow",signal:AbortSignal.timeout(12000)});
    return r.ok ? await r.text() : null;
  } catch { return null; }
}
function decode(s:string){
  return s.replace(/&nbsp;|&#160;/gi," ").replace(/&amp;/gi,"&").replace(/&quot;/gi,'"').replace(/&#39;|&apos;/gi,"'")
    .replace(/&lt;/gi,"<").replace(/&gt;/gi,">").replace(/&#(\d+);/g,(_,n)=>String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi,(_,n)=>String.fromCodePoint(parseInt(n,16)));
}
function plain(h:string){ return decode(h.replace(/<script\b[\s\S]*?<\/script>/gi," ").replace(/<style\b[\s\S]*?<\/style>/gi," ").replace(/<[^>]+>/g," ")).replace(/\s+/g," ").trim(); }
function meta(h:string,key:string){
  const e=key.replace(/[.*+?^${}()|[\]\\]/g,"\\$&");
  for(const re of [
    new RegExp(`<meta\\b[^>]*(?:property|name|itemprop)=["']${e}["'][^>]*content=["']([^"']+)["']`,`i`),
    new RegExp(`<meta\\b[^>]*content=["']([^"']+)["'][^>]*(?:property|name|itemprop)=["']${e}["']`,`i`)
  ]){ const m=h.match(re); if(m) return decode(m[1].trim()); }
  return null;
}
function norm(s:string|null){ return (s??"").normalize("NFKC").toLowerCase().trim().replace(/[أإآ]/g,"ا").replace(/ة/g,"ه").replace(/ى/g,"ي").replace(/[^0-9a-z\u0600-\u06ff]+/gi," ").replace(/\s+/g," ").trim(); }
function numericPrice(v:unknown){
  if(v==null) return null;
  const m=String(v).trim().replace(/٬/g,"").replace(/,/g,"").replace(/٫/g,".").match(/(?:^|\D)(\d{1,7}(?:\.\d{1,2})?)(?:\D|$)/);
  if(!m) return null;
  const n=Number(m[1]);
  return Number.isFinite(n)&&n>0&&n<=100000 ? Math.round(n*100)/100 : null;
}
function currencyPrice(text:string){
  const clean=text.replace(/٬/g,"").replace(/٫/g,".");
  for(const re of [
    /(?:SAR|ر\.?\s?س\.?|ريال(?:\s+سعودي)?)\s*[:\-]?\s*(\d{1,7}(?:[.,]\d{1,2})?)/i,
    /(\d{1,7}(?:[.,]\d{1,2})?)\s*(?:SAR|ر\.?\s?س\.?|ريال(?:\s+سعودي)?)/i
  ]){
    const m=clean.match(re); if(m){ const n=numericPrice(m[1]); if(n!==null) return n; }
  }
  return null;
}
function foldBonus(text:string){
  return text.replace(/(\d+(?:[.,]\d+)?)\s*\+\s*(\d+(?:[.,]\d+)?)\s*(g|gm|gram|kg|ml|l|ltr)\b/gi,(_m,a0,b0,u0)=>{
    const a=Number(String(a0).replace(",",".")), b=Number(String(b0).replace(",",".")), u=String(u0).toLowerCase();
    if(!Number.isFinite(a)||!Number.isFinite(b)) return _m;
    const sum=Math.round((a+b)*1000)/1000;
    if(u==="kg") return `${sum*1000} g`;
    if(u==="l"||u==="ltr") return `${sum*1000} ml`;
    if(u==="g"||u==="gm"||u==="gram") return `${sum} g`;
    return `${sum} ml`;
  });
}
async function identity(name:string,brand:string):Promise<Identity|null>{
  if(ELECTRONICS.test(name)) return null;
  const source=foldBonus(`${name} ${brand}`), nb=norm(brand);
  let sv:number|null=null, su:string|null=null;
  for(const [p,u,m] of SIZES){ const x=source.match(p); if(x){ const n=Number(x[1].replace(",","."))*m; if(n>0&&n<=100000){sv=Math.round(n*1000)/1000;su=u;break;} } }
  let pc=1;
  for(const p of PACKS){ const x=source.match(p); if(x){ const n=Number(x[1]); if(n>=1&&n<=100){pc=n;break;} } }
  const toks=new Set(norm(source).split(" ").filter(Boolean)), variants:string[]=[];
  for(const [k,words] of Object.entries(VAR)) if(words.some(w=>toks.has(norm(w)))) variants.push(k);
  const variant=variants.length?[...new Set(variants)].sort().join("+"):null;
  if(!nb||sv===null||!su) return null;
  let stripped=foldBonus(name);
  for(const [p] of SIZES) stripped=stripped.replace(p," ");
  for(const p of PACKS) stripped=stripped.replace(p," ");
  const bt=new Set(nb.split(" ")), vt=new Set(Object.values(VAR).flat().map(norm));
  const family=[...new Set(norm(stripped).split(" ").filter(t=>t.length>1&&!/^\d+$/.test(t)&&!STOP.has(t)&&!bt.has(t)&&!vt.has(t)))].sort().slice(0,6);
  if(!family.length&&!variant) return null;
  const raw=[nb,variant??"standard",`${String(sv)}:${su}`,String(pc),family.join(",")].join("|");
  return {key:`v2:${(await sha(raw)).slice(0,32)}`,variant,sv,su,pc};
}
function robotsRules(txt:string){
  const allow:string[]=[],deny:string[]=[]; let active=false;
  for(const raw of txt.split(/\r?\n/)){
    const line=raw.split("#",1)[0].trim(), i=line.indexOf(":"); if(i<0) continue;
    const k=line.slice(0,i).trim().toLowerCase(), v=line.slice(i+1).trim();
    if(k==="user-agent"){ const a=v.toLowerCase(); active=a==="*"||UA.toLowerCase().startsWith(a); continue; }
    if(!active) continue; if(k==="allow"&&v) allow.push(v); if(k==="disallow"&&v) deny.push(v);
  }
  return {allow,deny};
}
function allowed(url:string,rules:{allow:string[];deny:string[]}){
  let p="/"; try { const u=new URL(url); p=u.pathname+u.search; } catch { return false; }
  const match=(q:string)=>{ if(!q) return false; const e="^"+q.replace(/[.+?^${}()|[\]\\]/g,"\\$&").replace(/\*/g,".*").replace(/\$$/,"$"); try{return new RegExp(e).test(p);}catch{return p.startsWith(q);} };
  const a=rules.allow.filter(match).sort((x,y)=>y.length-x.length)[0]??"", d=rules.deny.filter(match).sort((x,y)=>y.length-x.length)[0]??"";
  return !d||a.length>=d.length;
}
function productUrls(h:string){
  const hrefs=[...h.matchAll(/href\s*=\s*["']([^"']+)["']/gi)].map(m=>decode(m[1]));
  const urls=hrefs.map(raw=>{ try { const u=new URL(raw,LIST); u.hash=""; return u.toString(); } catch { return ""; } })
    .filter(u=>/^https:\/\/gcc\.luluhypermarket\.com\/en-sa\/.+\/p\//i.test(u));
  return [...new Set(urls)];
}
async function parseProduct(h:string,url:string){
  const hm=h.match(/<h1\b[^>]*>([\s\S]{1,1200}?)<\/h1>/i); if(!hm||hm.index==null) return null;
  const name=plain(hm[1]); if(!name||name.length>240||ELECTRONICS.test(name)) return null;
  const before=h.slice(Math.max(0,hm.index-5500),hm.index);
  let brand:string|null=null;
  const bm=before.match(/View\s*all\s*products\s*from[\s\S]{0,1400}?<a\b[^>]*>([\s\S]{1,300}?)<\/a>/i); if(bm) brand=plain(bm[1]);
  if(!brand){ const m=plain(before).match(/View\s*all\s*products\s*from\s+([A-Za-z0-9&'’._\- ]{2,80})\s*$/i); if(m) brand=m[1].trim(); }
  if(!brand) return null;
  let amount=numericPrice(meta(h,"product:price:amount")??meta(h,"price")??meta(h,"og:price:amount"));
  if(amount===null){
    const end=hm.index+hm[0].length; let after=h.slice(end,end+6500);
    const marker=after.search(/Selected\s*Pack\s*Quantity|Product\s*Summary|Product\s*Information/i); if(marker>=0) after=after.slice(0,marker);
    amount=currencyPrice(plain(after));
  }
  if(amount===null) return null;
  const id=await identity(name,brand); if(!id) return null;
  return {name,brand,price:amount,currency:(meta(h,"product:price:currency")??"SAR").toUpperCase(),image:meta(h,"og:image"),id,url};
}

Deno.serve(async(req:Request)=>{
  const started=Date.now(), projectUrl=Deno.env.get("SUPABASE_URL")??"", key=secret();
  if(!projectUrl||!key) return Response.json({error:"server_config_missing"},{status:503});
  const db=createClient(projectUrl,key,{auth:{persistSession:false,autoRefreshToken:false}});
  const token=req.headers.get("x-ingest-token")??"";
  const {data:ctl}=await db.from("ingest_control").select("value").eq("key","cron_token_sha256").maybeSingle();
  if(!ctl?.value||(await sha(token))!==ctl.value) return Response.json({error:"unauthorized"},{status:401});
  const {data:r}=await db.from("retailers").select("id").eq("slug","lulu").eq("active",true).maybeSingle();
  if(!r) return Response.json({error:"retailer_inactive"},{status:409});
  const robotText=await fetchText(`${BASE}/robots.txt`,"text/plain,*/*;q=0.8");
  if(!robotText) return Response.json({error:"robots_unavailable"},{status:503});
  const rules=robotsRules(robotText); if(!allowed(LIST,rules)) return Response.json({error:"list_disallowed"},{status:403});
  const listHtml=await fetchText(LIST); if(!listHtml) return Response.json({error:"list_unavailable"},{status:503});
  let body:any={}; try{ body=await req.json(); }catch{}
  const max=Math.max(1,Math.min(Number(body.max_pages??8)||8,12));
  const urls=productUrls(listHtml).slice(0,max);
  let recorded=0,rejected=0,failed=0; const accepted:any[]=[];
  for(const url of urls){
    if(Date.now()-started>105000) break;
    if(!allowed(url,rules)){ rejected++; continue; }
    const h=await fetchText(url); if(!h){failed++;continue;}
    const p=await parseProduct(h,url); if(!p){rejected++;continue;}
    const {error}=await db.rpc("record_identity_price",{
      p_identity_key:p.id.key,p_name_ar:/[\u0600-\u06ff]/.test(p.name)?p.name:null,p_name_en:/[\u0600-\u06ff]/.test(p.name)?null:p.name,
      p_brand:p.brand,p_variant:p.id.variant,p_net_content_value:p.id.sv,p_net_content_unit:p.id.su,p_pack_count:p.id.pc,p_image_url:p.image,
      p_retailer_slug:"lulu",p_branch_key:"online",p_price:p.price,p_currency:p.currency,p_source_url:p.url
    });
    if(error){failed++;continue;}
    recorded++; accepted.push({name:p.name,brand:p.brand,price:p.price,url:p.url});
    await new Promise(res=>setTimeout(res,650));
  }
  await db.rpc("rebuild_product_snapshots");
  return Response.json({retailer:"lulu",source:"public_list",list_product_urls:productUrls(listHtml).length,attempted:urls.length,recorded,rejected,failed,accepted,elapsed_ms:Date.now()-started});
});

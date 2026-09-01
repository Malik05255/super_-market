import { createClient } from "npm:@supabase/supabase-js@2";

const UA="MoqarinAlasaarTamimi/2.0 (+cursor public price refresh)";
const BASE="https://shop.tamimimarkets.com/";
const SEEDS=[
  "https://shop.tamimimarkets.com/en/category/tamimi-product",
  "https://shop.tamimimarkets.com/en/category/spreads",
  "https://shop.tamimimarkets.com/en/category/spices",
  "https://shop.tamimimarkets.com/en/brand/tamimi-1"
];
const enc=new TextEncoder();
const STOP=new Set(["can","cans","bottle","bottles","pet","pack","packs","piece","pieces","pc","pcs","ml","ltr","liter","litre","g","gm","gram","kg","each","علبة","علب","عبوة","عبوات","زجاجة","زجاجات","حبة","حبات","مل","لتر","جرام","غرام","كيلو","كجم"]);
const VAR:Record<string,string[]>={zero:["zero","زيرو","صفر"],diet:["diet","دايت","حمية"],light:["light","لايت","خفيف"],original:["original","regular","classic","اصلي","أصلي","عادي","كلاسيك"],cherry:["cherry","كرز"],vanilla:["vanilla","فانيلا"],lemon:["lemon","ليمون"],lime:["lime","لايم"]};
const SIZES:Array<[RegExp,string,number]>=[[/\b(\d+(?:[.,]\d+)?)\s*(?:ml|مل|مليلتر)\b/i,"ml",1],[/\b(\d+(?:[.,]\d+)?)\s*(?:l|ltr|liter|litre|لتر)\b/i,"ml",1000],[/\b(\d+(?:[.,]\d+)?)\s*(?:g|gm|gram|جرام|غرام)\b/i,"g",1],[/\b(\d+(?:[.,]\d+)?)\s*(?:kg|kilogram|كيلو|كجم)\b/i,"g",1000]];
const PACKS=[/(?:^|\D)(\d{1,3})\s*[x×]\s*/i,/(?:pack|pk|عبوة|عبوات|حبة|حبات)\s*(?:of\s*)?(\d{1,3})(?!\d)/i,/(?:^|\D)(\d{1,3})\s*(?:pack|pk|pcs|pieces|حبة|حبات|عبوة|عبوات)(?!\w)/i];

type Identity={key:string;variant:string|null;sv:number;su:string;pc:number};
type Parsed={name:string;brand:string;size:string;identitySize:string;price:number;image:string|null;id:Identity;url:string};

function secret(){const m=Deno.env.get("SUPABASE_SECRET_KEYS");if(m){try{const p=JSON.parse(m);if(p?.default)return String(p.default)}catch{}}return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")??""}
async function hex(v:string){const h=await crypto.subtle.digest("SHA-256",enc.encode(v));return[...new Uint8Array(h)].map(x=>x.toString(16).padStart(2,"0")).join("")}
function host(v:string){try{return new URL(v).hostname.toLowerCase().replace(/^www\./,"")}catch{return""}}
async function fetchText(u:string,accept="text/html,*/*;q=0.8"){try{const r=await fetch(u,{headers:{"User-Agent":UA,Accept:accept,"Accept-Language":"en,ar;q=0.8"},redirect:"follow",signal:AbortSignal.timeout(12000)});return r.ok?await r.text():null}catch{return null}}
function decode(s:string){return s.replace(/&nbsp;|&#160;/gi," ").replace(/&amp;/gi,"&").replace(/&quot;/gi,'"').replace(/&#39;|&apos;/gi,"'").replace(/&lt;/gi,"<").replace(/&gt;/gi,">").replace(/&#(\d+);/g,(_,n)=>String.fromCodePoint(Number(n)))}
function plain(h:string){return decode(h.replace(/<script\b[\s\S]*?<\/script>/gi," ").replace(/<style\b[\s\S]*?<\/style>/gi," ").replace(/<[^>]+>/g," ")).replace(/\s+/g," ").trim()}
function meta(h:string,k:string){const e=k.replace(/[.*+?^${}()|[\]\\]/g,"\\$&");for(const r of [new RegExp(`<meta\\b[^>]*(?:property|name|itemprop)=["']${e}["'][^>]*content=["']([^"']+)["']`,`i`),new RegExp(`<meta\\b[^>]*content=["']([^"']+)["'][^>]*(?:property|name|itemprop)=["']${e}["']`,`i`)]){const m=h.match(r);if(m)return decode(m[1].trim())}return null}
function norm(s:string|null){return(s??"").normalize("NFKC").toLowerCase().trim().replace(/[أإآ]/g,"ا").replace(/ة/g,"ه").replace(/ى/g,"ي").replace(/[^0-9a-z\u0600-\u06ff]+/gi," ").replace(/\s+/g," ").trim()}
function price(v:string){const n=Number(v.replace(/,/g,"").replace(/٫/g,"."));return Number.isFinite(n)&&n>0&&n<=100000?Math.round(n*100)/100:null}
function canonicalUrl(raw:string){try{const u=new URL(raw,BASE);u.hash="";u.search="";if(host(u.toString())!=="shop.tamimimarkets.com")return null;if(/^\/product\//i.test(u.pathname))u.pathname=`/en${u.pathname}`;if(!/^\/en\/product\//i.test(u.pathname))return null;return u.toString().replace(/\/$/,"")}catch{return null}}
function links(h:string){const out:string[]=[];for(const m of h.matchAll(/href\s*=\s*["']([^"']+)["']/gi)){const u=canonicalUrl(decode(m[1]));if(u&&!out.includes(u))out.push(u)}return out}
function foldBonusSize(size:string){
  const m=size.trim().match(/^(\d+(?:[.,]\d+)?)\s*\+\s*(\d+(?:[.,]\d+)?)\s*(g|gm|gram|kg|ml|l|ltr)$/i);
  if(!m)return size;
  const a=Number(m[1].replace(",",".")),b=Number(m[2].replace(",",".")),unit=m[3].toLowerCase();
  if(!Number.isFinite(a)||!Number.isFinite(b)||a<=0||b<=0)return size;
  const sum=Math.round((a+b)*1000)/1000;
  if(unit==="kg")return `${sum*1000} G`;
  if(unit==="l"||unit==="ltr")return `${sum*1000} ML`;
  if(unit==="g"||unit==="gm"||unit==="gram")return `${sum} G`;
  return `${sum} ML`;
}
async function identity(name:string,brand:string,size:string):Promise<Identity|null>{
  const source=`${name} ${size} ${brand}`,nb=norm(brand);let sv:number|null=null,su:string|null=null;
  for(const[p,u,m]of SIZES){const x=source.match(p);if(x){const n=Number(x[1].replace(",","."))*m;if(n>0&&n<=100000){sv=Math.round(n*1000)/1000;su=u;break}}}
  let pc=1;for(const p of PACKS){const x=source.match(p);if(x){const n=Number(x[1]);if(n>=1&&n<=100){pc=n;break}}}
  const toks=new Set(norm(source).split(" ").filter(Boolean)),vs:string[]=[];for(const[k,w]of Object.entries(VAR))if(w.some(q=>toks.has(norm(q))))vs.push(k);const variant=vs.length?[...new Set(vs)].sort().join("+"):null;
  if(!nb||sv===null||!su)return null;
  let stripped=`${name} ${size}`;for(const[p]of SIZES)stripped=stripped.replace(p," ");for(const p of PACKS)stripped=stripped.replace(p," ");
  const bt=new Set(nb.split(" ")),vt=new Set(Object.values(VAR).flat().map(norm)),family=[...new Set(norm(stripped).split(" ").filter(t=>t.length>1&&!/^\d+$/.test(t)&&!STOP.has(t)&&!bt.has(t)&&!vt.has(t)))].sort().slice(0,6);
  if(!family.length&&!variant)return null;
  const raw=[nb,variant??"standard",`${String(sv)}:${su}`,String(pc),family.join(",")].join("|"),d=(await hex(raw)).slice(0,32);return{key:`v2:${d}`,variant,sv,su,pc};
}
async function parse(h:string,u:string):Promise<Parsed|null>{
  const all=plain(h),title=meta(h,"og:title")?.replace(/\s*\|\s*Tamimi Markets.*$/i,"").trim()??null;
  const info=all.match(/\bBrand\s+(.+?)\s+Size\s+(.+?)\s+Price\s+([^\s]+)\s+Additional Information\b/i);if(!title||!info)return null;
  const brand=info[1].trim(),size=info[2].trim(),priceText=info[3].trim(),brandPos=all.search(/\bBrand\b/i),pre=brandPos>=0?all.slice(Math.max(0,brandPos-600),brandPos):"";
  if(/\bSold Out\b/i.test(pre)||priceText==="-")return null;const amount=price(priceText);if(amount===null)return null;
  const identitySize=foldBonusSize(size),id=await identity(title,brand,identitySize);if(!id)return null;
  return{name:`${title}-${size}`,brand,size,identitySize,price:amount,image:meta(h,"og:image"),id,url:u};
}
function robotsRules(txt:string){const allow:string[]=[],deny:string[]=[];let active=false;for(const raw of txt.split(/\r?\n/)){const line=raw.split("#",1)[0].trim(),i=line.indexOf(":");if(i<0)continue;const k=line.slice(0,i).trim().toLowerCase(),v=line.slice(i+1).trim();if(k==="user-agent"){const a=v.toLowerCase();active=a==="*"||UA.toLowerCase().startsWith(a);continue}if(!active)continue;if(k==="allow"&&v)allow.push(v);if(k==="disallow"&&v)deny.push(v)}return{allow,deny}}
function allowed(u:string,r:{allow:string[];deny:string[]}){let p="/";try{const x=new URL(u);p=x.pathname+x.search}catch{return false}const m=(q:string)=>{if(!q)return false;const e="^"+q.replace(/[.+?^${}()|[\]\\]/g,"\\$&").replace(/\*/g,".*").replace(/\$$/,"$");try{return new RegExp(e).test(p)}catch{return p.startsWith(q)}};const a=r.allow.filter(m).sort((x,y)=>y.length-x.length)[0]??"",d=r.deny.filter(m).sort((x,y)=>y.length-x.length)[0]??"";return !d||a.length>=d.length}
function circular<T>(items:T[],start:number,count:number){const out:T[]=[];if(!items.length)return out;for(let i=0;i<Math.min(count,items.length);i++)out.push(items[(start+i)%items.length]);return out}
const sleep=(ms:number)=>new Promise(r=>setTimeout(r,ms));

Deno.serve(async(req:Request)=>{
  const started=Date.now(),projectUrl=Deno.env.get("SUPABASE_URL")??"",key=secret();if(!projectUrl||!key)return Response.json({error:"server_config_missing"},{status:503});
  const db=createClient(projectUrl,key,{auth:{persistSession:false,autoRefreshToken:false}}),token=req.headers.get("x-ingest-token")??"",{data:ctl}=await db.from("ingest_control").select("value").eq("key","cron_token_sha256").maybeSingle();if(!ctl?.value||(await hex(token))!==ctl.value)return Response.json({error:"unauthorized"},{status:401});
  const robotText=await fetchText(new URL("/robots.txt",BASE).toString(),"text/plain,*/*;q=0.8");if(!robotText)return Response.json({retailer:"tamimi",status:"robots_unavailable"});const rr=robotsRules(robotText);
  let body:any={};try{body=await req.json()}catch{}const max=Math.max(4,Math.min(Number(body.max_pages??20)||20,24));
  const {data:r}=await db.from("retailers").select("id").eq("slug","tamimi").eq("active",true).maybeSingle();if(!r)return Response.json({error:"retailer_inactive"},{status:409});
  const discovered:string[]=[];for(const seed of SEEDS){if(!allowed(seed,rr))continue;const h=await fetchText(seed);if(!h)continue;for(const u of links(h))if(!discovered.includes(u))discovered.push(u)}
  const refreshSlots=Math.min(4,Math.max(1,Math.floor(max/4))),{data:known}=await db.from("retailer_identity_sources").select("id,source_url,branch_key,last_checked_at").eq("retailer_id",r.id).eq("active",true).order("last_checked_at",{ascending:true,nullsFirst:true}).limit(refreshSlots),knownSet=new Set((known??[]).map((x:any)=>String(x.source_url))),pool=discovered.filter(u=>!knownSet.has(u));
  const {data:cursorRow}=await db.from("retailer_scan_cursors").select("cursor_position").eq("retailer_id",r.id).eq("stream","identity").maybeSingle(),cursor=Number(cursorRow?.cursor_position??0),scanSlots=Math.max(1,max-(known?.length??0)),batch=circular(pool,pool.length?cursor%pool.length:0,scanSlots);
  let recorded=0,refreshed=0,rejected=0,failed=0;const accepted:any[]=[];
  async function one(u:string,sourceId:number|null,branch="online"){
    if(!allowed(u,rr)){rejected++;return}const h=await fetchText(u);if(!h){failed++;return}const p=await parse(h,u);if(!p){rejected++;if(sourceId)await db.from("retailer_identity_sources").update({last_checked_at:new Date().toISOString(),last_error:"identity_or_price_not_verified"}).eq("id",sourceId);return}
    const {error}=await db.rpc("record_identity_price",{p_identity_key:p.id.key,p_name_ar:null,p_name_en:p.name,p_brand:p.brand,p_variant:p.id.variant,p_net_content_value:p.id.sv,p_net_content_unit:p.id.su,p_pack_count:p.id.pc,p_image_url:p.image,p_retailer_slug:"tamimi",p_branch_key:branch,p_price:p.price,p_currency:"SAR",p_source_url:p.url});if(error){failed++;return}
    accepted.push({name:p.name,brand:p.brand,size:p.size,identity_size:p.identitySize,price:p.price,url:p.url});if(sourceId){await db.from("retailer_identity_sources").update({last_checked_at:new Date().toISOString(),last_error:null}).eq("id",sourceId);refreshed++}else recorded++;
  }
  for(const s of known??[]){if(Date.now()-started>112000)break;await one(String(s.source_url),Number(s.id),String(s.branch_key??"online"));await sleep(750)}for(const u of batch){if(Date.now()-started>112000)break;await one(u,null);await sleep(750)}
  const next=pool.length?(cursor+batch.length)%pool.length:0;await db.from("retailer_scan_cursors").upsert({retailer_id:r.id,stream:"identity",cursor_position:next,catalog_size:pool.length,updated_at:new Date().toISOString()},{onConflict:"retailer_id,stream"});await db.rpc("rebuild_product_snapshots");
  return Response.json({retailer:"tamimi",recorded,refreshed,rejected,failed,accepted:accepted.slice(0,10),catalog_candidates:discovered.length,cursor_before:cursor,cursor_after:next,scanned_new:batch.length,elapsed_ms:Date.now()-started});
});

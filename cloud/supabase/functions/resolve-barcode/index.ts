import { createClient } from "npm:@supabase/supabase-js@2";

const SFDA_SEARCH_URL = "https://api.sfda.gov.sa:9001/v2/FIRS/food/search";
const SFDA_PUBLIC_API_KEY = "dZRGUbnQeaDndOXk9hX9GFCxXzCSraEz";
const USER_AGENT = "MoqarinAlasaar/2.2 (Saudi barcode identity resolver; GitHub Malik05255/super_-market)";
const MAX_EXTERNAL_PER_MINUTE = 10;
const RESOLVED_COOLDOWN_MS = 12 * 60 * 60 * 1000;
const MISS_COOLDOWN_MS = 5 * 60 * 1000;
const UPCITEMDB_MINUTE_LIMIT = 5;
const UPCITEMDB_DAILY_LIMIT = 95;
const enc = new TextEncoder();

const FACTS_SOURCES = [
  { id: "open_food_facts", label: "Open Food Facts", base: "https://world.openfoodfacts.org/api/v3/product/" },
  { id: "open_products_facts", label: "Open Products Facts", base: "https://world.openproductsfacts.org/api/v3/product/" },
  { id: "open_beauty_facts", label: "Open Beauty Facts", base: "https://world.openbeautyfacts.org/api/v3/product/" },
  { id: "open_pet_food_facts", label: "Open Pet Food Facts", base: "https://world.openpetfoodfacts.org/api/v3/product/" },
] as const;

const STOP = new Set([
  "can","cans","bottle","bottles","pet","pack","packs","piece","pieces","pc","pcs",
  "ml","cl","ltr","liter","litre","g","gm","gram","kg",
  "علبة","علب","عبوة","عبوات","زجاجة","زجاجات","حبة","حبات","مل","لتر","جرام","غرام","كيلو","كجم"
]);
const VAR: Record<string,string[]> = {
  zero:["zero","زيرو","صفر"], diet:["diet","دايت","حمية"], light:["light","لايت","خفيف"],
  original:["original","regular","classic","اصلي","أصلي","عادي","كلاسيك"],
  cherry:["cherry","كرز"], vanilla:["vanilla","فانيلا"], lemon:["lemon","ليمون"], lime:["lime","لايم"]
};
const SIZES: Array<[RegExp,string,number]> = [
  [/\b(\d+(?:[.,]\d+)?)\s*(?:ml|مل|مليلتر|millilit(?:er|re)s?)\b/i,"ml",1],
  [/\b(\d+(?:[.,]\d+)?)\s*(?:cl|centilitre|centiliter)\b/i,"ml",10],
  [/\b(\d+(?:[.,]\d+)?)\s*(?:l|ltr|liter|litre|لتر)\b/i,"ml",1000],
  [/\b(\d+(?:[.,]\d+)?)\s*(?:g|gm|gram|جرام|غرام)\b/i,"g",1],
  [/\b(\d+(?:[.,]\d+)?)\s*(?:kg|kilogram|كيلو|كجم)\b/i,"g",1000]
];
const PACKS = [
  /(?:^|\D)(\d{1,3})\s*[x×]\s*/i,
  /(?:pack|pk|عبوة|عبوات|حبة|حبات)\s*(?:of\s*)?(\d{1,3})(?!\d)/i,
  /(?:^|\D)(\d{1,3})\s*(?:pack|pk|pcs|pieces|حبة|حبات|عبوة|عبوات)(?!\w)/i
];

type Identity = {
  key:string|null; variant:string|null; sizeValue:number|null; sizeUnit:string|null;
  packCount:number; confidence:number; method:string
};
type GenericProduct = {
  barcode:string; nameAr:string|null; nameEn:string|null; brand:string|null;
  quantity:string|null; image:string|null; source:string; description?:string|null;
  category?:string|null; manufacturingCountry?:string|null; manufacturingPlaces?:string|null;
  ingredients?:string|null; allergens?:string[]; nutritionGrade?:string|null;
  novaGroup?:number|null; positiveNotes?:string[]; cautionNotes?:string[];
  updatedAt?:string|null;
};

function secretKey(){
  const modern=Deno.env.get("SUPABASE_SECRET_KEYS");
  if(modern){try{const parsed=JSON.parse(modern);if(parsed?.default)return String(parsed.default)}catch{}}
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")??"";
}
function validGtin(value:unknown):string|null{
  if(value==null)return null;
  const d=String(value).replace(/\D+/g,"");
  if(![8,12,13,14].includes(d.length))return null;
  let total=0;
  [...d.slice(0,-1)].reverse().forEach((x,i)=>total+=Number(x)*(i%2===0?3:1));
  return Number(d.at(-1))===(10-total%10)%10?d:null;
}
function restrictedCirculation(barcode:string){
  return (barcode.length===12 && barcode.startsWith("2")) ||
    (barcode.length===13 && Number(barcode.slice(0,2))>=20 && Number(barcode.slice(0,2))<=29);
}
function norm(value:string|null|undefined){
  return(value??"").normalize("NFKC").toLowerCase().trim()
    .replace(/[أإآ]/g,"ا").replace(/ة/g,"ه").replace(/ى/g,"ي")
    .replace(/[^0-9a-z\u0600-\u06ff]+/gi," ").replace(/\s+/g," ").trim();
}
async function sha(value:string){
  const h=await crypto.subtle.digest("SHA-256",enc.encode(value));
  return[...new Uint8Array(h)].map(x=>x.toString(16).padStart(2,"0")).join("");
}
async function identity(name:string|null,brand:string|null,quantity:string|null,method:string):Promise<Identity>{
  const identityName=[name,quantity].filter(Boolean).join(" ");
  const source=[identityName,brand].filter(Boolean).join(" ");
  const nb=norm(brand);
  let sv:number|null=null,su:string|null=null;
  for(const[p,u,m]of SIZES){
    const x=source.match(p);
    if(x){
      const n=Number(x[1].replace(",","."))*m;
      if(n>0&&n<=100000){sv=Math.round(n*1000)/1000;su=u;break}
    }
  }
  let pc=1;
  for(const p of PACKS){
    const x=source.match(p);
    if(x){const n=Number(x[1]);if(n>=1&&n<=100){pc=n;break}}
  }
  const tokens=new Set(norm(source).split(" ").filter(Boolean)),variants:string[]=[];
  for(const[k,words]of Object.entries(VAR))if(words.some(w=>tokens.has(norm(w))))variants.push(k);
  const variant=variants.length?[...new Set(variants)].sort().join("+"):null;
  if(!nb||sv===null||!su)return{key:null,variant,sizeValue:sv,sizeUnit:su,packCount:pc,confidence:0,method:"isolated_missing_identity_fields"};
  let stripped=identityName;
  for(const[p]of SIZES)stripped=stripped.replace(p," ");
  for(const p of PACKS)stripped=stripped.replace(p," ");
  const brandTokens=new Set(nb.split(" "));
  const variantTokens=new Set(Object.values(VAR).flat().map(norm));
  const family=[...new Set(norm(stripped).split(" ").filter(t=>
    t.length>1&&!/^\d+$/.test(t)&&!STOP.has(t)&&!brandTokens.has(t)&&!variantTokens.has(t)
  ))].sort().slice(0,6);
  if(!family.length&&!variant)return{key:null,variant,sizeValue:sv,sizeUnit:su,packCount:pc,confidence:0,method:"isolated_ambiguous_family"};
  const raw=[nb,variant??"standard",`${String(sv)}:${su}`,String(pc),family.join(",")].join("|");
  const digest=(await sha(raw)).slice(0,32);
  return{key:`v2:${digest}`,variant,sizeValue:sv,sizeUnit:su,packCount:pc,confidence:variant?0.96:0.92,method};
}
function firstText(...values:unknown[]){
  for(const value of values){
    if(typeof value==="string"&&value.trim())return value.trim();
    if(typeof value==="number"&&Number.isFinite(value))return String(value);
  }
  return null;
}
function firstBrand(value:unknown){
  if(typeof value!=="string")return null;
  return value.split(",").map(x=>x.trim()).find(Boolean)??null;
}
function cleanTag(tag:unknown){
  if(typeof tag!=="string")return null;
  const v=tag.replace(/^[a-z]{2}:/i,"").replace(/-/g," ").trim();
  return v||null;
}
function nutritionNotes(levels:unknown){
  if(!levels||typeof levels!=="object")return{positive:[] as string[],caution:[] as string[]};
  const labels:Record<string,{low:string;high:string}>={
    sugars:{low:"السكريات منخفضة حسب البيانات المفتوحة",high:"السكريات مرتفعة حسب البيانات المفتوحة"},
    salt:{low:"الملح منخفض حسب البيانات المفتوحة",high:"الملح مرتفع حسب البيانات المفتوحة"},
    fat:{low:"الدهون منخفضة حسب البيانات المفتوحة",high:"الدهون مرتفعة حسب البيانات المفتوحة"},
    "saturated-fat":{low:"الدهون المشبعة منخفضة حسب البيانات المفتوحة",high:"الدهون المشبعة مرتفعة حسب البيانات المفتوحة"}
  };
  const positive:string[]=[],caution:string[]=[];
  for(const[key,label]of Object.entries(labels)){
    const level=String((levels as Record<string,unknown>)[key]??"").toLowerCase();
    if(level==="low")positive.push(label.low);
    if(level==="high")caution.push(label.high);
  }
  return{positive,caution};
}
function snapshotHasPrice(payload:any){
  return !!payload&&(payload.current_price!=null||(Array.isArray(payload.offers)&&payload.offers.length>0));
}
async function setAttempt(db:any,barcode:string,status:string,count:number){
  const now=new Date().toISOString();
  await db.from("barcode_resolution_attempts").upsert({
    barcode,last_attempt_at:now,attempt_count:Math.max(1,count),last_status:status,updated_at:now
  },{onConflict:"barcode"});
}
function cooldownFor(status:string|null|undefined){
  return status?.startsWith("resolved") ? RESOLVED_COOLDOWN_MS : MISS_COOLDOWN_MS;
}

function walkObjects(value:unknown,out:Record<string,unknown>[]){
  if(Array.isArray(value)){for(const child of value)walkObjects(child,out);return}
  if(!value||typeof value!=="object")return;
  const obj=value as Record<string,unknown>;out.push(obj);
  for(const child of Object.values(obj))if(child&&typeof child==="object")walkObjects(child,out);
}
function pick(obj:Record<string,unknown>,...keys:string[]){
  for(const key of keys)if(obj[key]!=null&&String(obj[key]).trim())return obj[key];
  return null;
}
function findSfdaExact(raw:unknown,barcode:string):Record<string,unknown>|null{
  const objects:Record<string,unknown>[]=[];walkObjects(raw,objects);
  for(const obj of objects){
    const candidate=validGtin(pick(obj,"barcode","Barcode","barCode","ean","EAN","gtin","GTIN"));
    if(candidate===barcode)return obj;
  }
  return null;
}
async function fetchSfda(barcode:string):Promise<GenericProduct|null>{
  const url=new URL(SFDA_SEARCH_URL);
  url.searchParams.set("apikey",SFDA_PUBLIC_API_KEY);
  url.searchParams.set("Barcode",barcode);
  let response:Response;
  try{
    response=await fetch(url,{headers:{"User-Agent":USER_AGENT,Accept:"application/json"},redirect:"follow",signal:AbortSignal.timeout(6500)});
  }catch{return null}
  if(!response.ok)return null;
  let raw:unknown;try{raw=await response.json()}catch{return null}
  const obj=findSfdaExact(raw,barcode);if(!obj)return null;
  const nameAr=firstText(pick(obj,"tradeNameAr","TradeNameAr","itemDescriptionAr","ItemDescriptionAr"));
  const nameEn=firstText(pick(obj,"tradeName","tradeNameEn","TradeName","TradeNameEn","itemDescription","itemDescriptionEn","ItemDescription","ItemDescriptionEn"));
  const weight=firstText(pick(obj,"itemWeight","ItemWeight","weight","Weight"));
  const unit=firstText(pick(obj,"unitNameEn","unitNameAr","UnitNameEn","UnitNameAr","unit","Unit"));
  const warning=firstText(pick(obj,"warningsAr","warningsEn","warnings","WarningsAr","WarningsEn","Warnings"));
  return {
    barcode,nameAr,nameEn,
    brand:firstText(pick(obj,"brandName","brandNameEn","brandNameAr","BrandName","BrandNameEn","BrandNameAr")),
    quantity:[weight,unit].filter(Boolean).join(" ")||null,
    image:firstText(pick(obj,"image","imageUrl","Image","ImageUrl")),
    manufacturingCountry:firstText(pick(obj,"countryOfOriginEn","countryOfOriginAr","countryOfOrigin","CountryOfOriginEn","CountryOfOriginAr","CountryOfOrigin")),
    ingredients:firstText(pick(obj,"ingredientsAr","ingredientsEn","ingredients","IngredientsAr","IngredientsEn","Ingredients")),
    cautionNotes:warning?[warning]:[],
    source:"Saudi Food and Drug Authority (SFDA)"
  };
}

async function fetchFacts(base:string,barcode:string):Promise<any|null>{
  const fields=[
    "code","product_name","product_name_en","product_name_ar","brands","quantity",
    "product_quantity","product_quantity_unit","image_front_url","image_url",
    "manufacturing_places","ingredients_text","ingredients_text_en","ingredients_text_ar",
    "allergens_tags","nutrition_grades","nova_group","nutrient_levels","last_modified_t",
    "categories","categories_tags","countries","generic_name","generic_name_en"
  ].join(",");
  let response:Response;
  try{
    response=await fetch(`${base}${barcode}?fields=${encodeURIComponent(fields)}`,{
      headers:{"User-Agent":USER_AGENT,Accept:"application/json"},redirect:"follow",signal:AbortSignal.timeout(7000)
    });
  }catch{return null}
  if(!response.ok)return null;
  try{
    const raw=await response.json();
    const product=raw?.product??raw;
    if(!product||typeof product!=="object")return null;
    const returned=validGtin(product.code);
    if(returned&&returned!==barcode)return null;
    if(raw?.status===0||raw?.status_verbose==="product not found")return null;
    if(!firstText(product.product_name_ar,product.product_name_en,product.product_name,product.generic_name_en,product.generic_name))return null;
    return raw;
  }catch{return null}
}
function factsToProduct(barcode:string,raw:any,label:string):GenericProduct|null{
  const product=raw?.product??raw;
  if(!product||typeof product!=="object")return null;
  const notes=nutritionNotes(product.nutrient_levels);
  const allergens=Array.isArray(product.allergens_tags)?product.allergens_tags.map(cleanTag).filter(Boolean) as string[]:[];
  const nameAr=firstText(product.product_name_ar);
  const nameEn=firstText(product.product_name_en,product.product_name,product.generic_name_en,product.generic_name);
  if(!nameAr&&!nameEn)return null;
  return {
    barcode,nameAr,nameEn,brand:firstBrand(product.brands),
    quantity:firstText(product.quantity,product.product_quantity&&product.product_quantity_unit?`${product.product_quantity} ${product.product_quantity_unit}`:null),
    image:firstText(product.image_front_url,product.image_url),
    manufacturingPlaces:firstText(product.manufacturing_places),
    ingredients:firstText(product.ingredients_text_ar,product.ingredients_text_en,product.ingredients_text),
    allergens,positiveNotes:notes.positive,cautionNotes:notes.caution,
    nutritionGrade:firstText(product.nutrition_grades),
    novaGroup:Number.isFinite(Number(product.nova_group))?Number(product.nova_group):null,
    category:firstText(product.categories),
    source:label,
    updatedAt:Number.isFinite(Number(product.last_modified_t))?new Date(Number(product.last_modified_t)*1000).toISOString():null
  };
}

async function fetchUpcItemDb(barcode:string):Promise<GenericProduct|null>{
  const url=new URL("https://api.upcitemdb.com/prod/trial/lookup");
  url.searchParams.set("upc",barcode);
  let response:Response;
  try{
    response=await fetch(url,{headers:{"User-Agent":USER_AGENT,Accept:"application/json"},redirect:"follow",signal:AbortSignal.timeout(7000)});
  }catch{return null}
  if(!response.ok)return null;
  let raw:any;try{raw=await response.json()}catch{return null}
  const items=Array.isArray(raw?.items)?raw.items:[];
  for(const item of items){
    const candidates=[item?.ean,item?.upc,item?.gtin].map(validGtin).filter(Boolean);
    if(!candidates.includes(barcode))continue;
    const title=firstText(item?.title,item?.description);
    if(!title)continue;
    return {
      barcode,nameAr:null,nameEn:title,brand:firstText(item?.brand),
      quantity:firstText(item?.weight,item?.dimension),
      image:Array.isArray(item?.images)?firstText(...item.images):null,
      description:firstText(item?.description),
      category:firstText(item?.category),
      source:"UPCitemdb"
    };
  }
  return null;
}

async function writeProduct(db:any,product:GenericProduct,method:string){
  const name=product.nameAr??product.nameEn;
  const id=await identity(name,product.brand,product.quantity,method);
  const {error}=await db.rpc("upsert_product_metadata",{
    p_barcode:product.barcode,p_name_ar:product.nameAr,p_name_en:product.nameEn,p_brand:product.brand,
    p_image_url:product.image,p_gs1_verified:false,p_identity_key:id.key,p_variant:id.variant,
    p_net_content_value:id.sizeValue,p_net_content_unit:id.sizeUnit,p_pack_count:id.packCount,
    p_match_confidence:id.confidence,p_match_method:id.method
  });
  if(error)return false;
  const info:any={
    manufacturing_country:product.manufacturingCountry,
    manufacturing_places:product.manufacturingPlaces,
    ingredients:product.ingredients,
    allergens:product.allergens??[],
    positive_notes:product.positiveNotes??[],
    caution_notes:product.cautionNotes??[],
    nutrition_grade:product.nutritionGrade,
    nova_group:product.novaGroup,
    category:product.category,
    description:product.description,
    data_source:product.source,
    updated_at:product.updatedAt??new Date().toISOString()
  };
  for(const k of Object.keys(info))if(info[k]===null||info[k]===""||(Array.isArray(info[k])&&!info[k].length))delete info[k];
  if(Object.keys(info).length>2)await db.rpc("upsert_product_info",{p_barcode:product.barcode,p_product_info:info});
  return true;
}

async function rebuiltSnapshot(db:any,barcode:string){
  await db.rpc("rebuild_product_snapshots");
  const {data}=await db.from("product_snapshots").select("payload").eq("barcode",barcode).maybeSingle();
  return data?.payload??null;
}

Deno.serve(async(req:Request)=>{
  if(req.method!=="POST")return Response.json({error:"method_not_allowed"},{status:405});
  const projectUrl=Deno.env.get("SUPABASE_URL")??"",key=secretKey();
  if(!projectUrl||!key)return Response.json({error:"server_config_missing"},{status:503});
  const db=createClient(projectUrl,key,{auth:{persistSession:false,autoRefreshToken:false}});
  let body:any={};try{body=await req.json()}catch{}
  const barcode=validGtin(body.barcode);
  if(!barcode)return Response.json({status:"invalid_barcode",payload:null},{status:400});
  if(restrictedCirculation(barcode))return Response.json({status:"restricted_circulation_barcode",payload:null});

  const {data:existing}=await db.from("product_snapshots").select("payload").eq("barcode",barcode).maybeSingle();
  if(existing?.payload)return Response.json({
    status:snapshotHasPrice(existing.payload)?"cached_with_price":"cached_metadata_only",identity_source:"snapshot",payload:existing.payload
  });

  const {data:attempt}=await db.from("barcode_resolution_attempts")
    .select("last_attempt_at,attempt_count,last_status").eq("barcode",barcode).maybeSingle();
  if(attempt?.last_attempt_at&&Date.now()-Date.parse(attempt.last_attempt_at)<cooldownFor(attempt.last_status)){
    return Response.json({status:attempt.last_status??"cooldown",payload:null});
  }

  const sinceMinute=new Date(Date.now()-60_000).toISOString();
  const sinceDay=new Date(Date.now()-24*60*60*1000).toISOString();
  const {count:minuteCount}=await db.from("barcode_resolution_attempts").select("barcode",{count:"exact",head:true}).gte("last_attempt_at",sinceMinute);
  if((minuteCount??0)>=MAX_EXTERNAL_PER_MINUTE)return Response.json({status:"rate_limited",payload:null},{status:429});
  const {count:dayCount}=await db.from("barcode_resolution_attempts").select("barcode",{count:"exact",head:true}).gte("last_attempt_at",sinceDay);

  const attemptCount=Number(attempt?.attempt_count??0)+1;
  await setAttempt(db,barcode,"started",attemptCount);

  const sfda=await fetchSfda(barcode);
  if(sfda&&await writeProduct(db,sfda,"sfda_registered_food_identity_v2")){
    const payload=await rebuiltSnapshot(db,barcode);
    if(payload){
      const status=snapshotHasPrice(payload)?"resolved_with_price":"resolved_metadata_only";
      await setAttempt(db,barcode,status,attemptCount);
      return Response.json({status,identity_source:"sfda",payload});
    }
  }

  for(const source of FACTS_SOURCES){
    const raw=await fetchFacts(source.base,barcode);
    if(!raw)continue;
    const product=factsToProduct(barcode,raw,source.label);
    if(!product)continue;
    if(await writeProduct(db,product,`${source.id}_identity_v2`)){
      const payload=await rebuiltSnapshot(db,barcode);
      if(payload){
        const status=snapshotHasPrice(payload)?"resolved_with_price":"resolved_metadata_only";
        await setAttempt(db,barcode,status,attemptCount);
        return Response.json({status,identity_source:source.id,payload});
      }
    }
  }

  if((minuteCount??0)<UPCITEMDB_MINUTE_LIMIT&&(dayCount??0)<UPCITEMDB_DAILY_LIMIT){
    const upc=await fetchUpcItemDb(barcode);
    if(upc&&await writeProduct(db,upc,"upcitemdb_identity_v2")){
      const payload=await rebuiltSnapshot(db,barcode);
      if(payload){
        const status=snapshotHasPrice(payload)?"resolved_with_price":"resolved_metadata_only";
        await setAttempt(db,barcode,status,attemptCount);
        return Response.json({status,identity_source:"upcitemdb",payload});
      }
    }
  }

  await setAttempt(db,barcode,"not_found",attemptCount);
  return Response.json({status:"not_found",identity_source:null,payload:null});
});

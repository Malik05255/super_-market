import { createClient } from "npm:@supabase/supabase-js@2";

const ACTIVE_RETAILERS = new Set(["lulu", "tamimi"]);
const MAX_TEXT_LENGTH = 4000;
const FRESH_HOURS = 72;

function secretKey(){
  const modern=Deno.env.get("SUPABASE_SECRET_KEYS");
  if(modern){try{const parsed=JSON.parse(modern);if(parsed?.default)return String(parsed.default)}catch{}}
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")??"";
}
function asciiDigits(value:string){
  const ar="٠١٢٣٤٥٦٧٨٩",fa="۰۱۲۳۴۵۶۷۸۹";
  return value.replace(/[٠-٩]/g,c=>String(ar.indexOf(c)))
    .replace(/[۰-۹]/g,c=>String(fa.indexOf(c)))
    .replace(/٫/g,".").replace(/٬/g,",");
}
function canonicalWords(value:string){
  let s=value;
  const replacements:Array<[RegExp,string]>=[
    [/\bpepsi\b|بيبسي|ببسي/gi," pepsi "],
    [/\bbarilla\b|باريلا/gi," barilla "],
    [/\bnutella\b|نوتيلا/gi," nutella "],
    [/\bgoody\b|قودي|جودي/gi," goody "],
    [/\baachi\b|اتشي|اشي/gi," aachi "],
    [/\bgerber\b|جيربر/gi," gerber "],
    [/\bglade\b|جلاد|جليد/gi," glade "],
    [/\bhuggies\b|هاجيز/gi," huggies "],
    [/\bbonne\s+maman\b|بون\s*مامان/gi," bonnemaman "],
    [/\bal\s+balad\b|البلد/gi," albalad "],
    [/\bal\s+hadiqa\b|الحديق[هة]/gi," alhadiqa "],
    [/\bhalwani\s+brothers\b|حلواني\s*(?:اخوان|إخوان)/gi," halwani "],
    [/\bal\s+shifa\b|الشفاء/gi," alshifa "],
    [/تميمي|اسواق\s+التميمي/gi," tamimi "],
    [/لولو/gi," lulu "],
    [/مايونيز/gi," mayonnaise "],
    [/طحين[هة]/gi," tahina "],
    [/زبد[هة]\s+فول\s+سوداني/gi," peanut butter "],
    [/فول\s+سوداني/gi," peanut "],
    [/فراول[هة]/gi," strawberry "],
    [/مرب[ىي]/gi," jam "],
    [/شوكولات[هة]/gi," chocolate "],
    [/بندق/gi," hazelnut "],
    [/عسل/gi," honey "],
    [/ملح/gi," salt "],
    [/دايت/gi," diet "],
    [/لايت/gi," light "],
    [/اصلي|أصلي/gi," original "],
  ];
  for(const[re,to]of replacements)s=s.replace(re,to);
  return s;
}
function norm(value:string|null|undefined){
  return canonicalWords(asciiDigits((value??"").normalize("NFKC").toLowerCase()))
    .replace(/[أإآ]/g,"ا").replace(/[ؤ]/g,"و").replace(/[ئ]/g,"ي")
    .replace(/ة/g,"ه").replace(/ى/g,"ي").replace(/[\u0640\u064b-\u065f\u0670]/g,"")
    .replace(/[^0-9a-z\u0600-\u06ff.]+/gi," ").replace(/\s+/g," ").trim();
}
const STOP=new Set(["the","and","with","for","pack","value","new","product","fresh",
  "علبه","عبوه","منتج","جديد","مع","من","في","على"]);
function tokens(value:string|null|undefined){
  return [...new Set(norm(value).split(" ").filter(t=>t.length>=2&&!STOP.has(t)))];
}
type Size={value:number;unit:"g"|"ml"};
function sizes(value:string):Size[]{
  const s=norm(value),out:Size[]=[];
  const patterns:Array<[RegExp,"g"|"ml",number]>=[
    [/\b(\d+(?:[.]\d+)?)\s*(?:g|gm|gram|جرام|غرام)\b/gi,"g",1],
    [/\b(\d+(?:[.]\d+)?)\s*(?:kg|kilogram|كيلو|كجم)\b/gi,"g",1000],
    [/\b(\d+(?:[.]\d+)?)\s*(?:ml|مل|مليلتر)\b/gi,"ml",1],
    [/\b(\d+(?:[.]\d+)?)\s*(?:l|ltr|liter|litre|لتر)\b/gi,"ml",1000],
  ];
  for(const[re,unit,m]of patterns)for(const x of s.matchAll(re)){
    const n=Number(x[1])*m;
    if(n>0&&n<=100000)out.push({value:Math.round(n*1000)/1000,unit});
  }
  return out;
}
function overlap(a:string[],b:Set<string>){
  if(!a.length)return 0;
  return a.filter(t=>b.has(t)).length/a.length;
}
function sizeMatches(product:any,observed:Size[]){
  if(product.net_content_value==null||!product.net_content_unit)return observed.length?0.5:0.45;
  const unit=String(product.net_content_unit).toLowerCase();
  const target=Number(product.net_content_value);
  return observed.some(x=>x.unit===unit&&Math.abs(x.value-target)<0.001)?1:0;
}
function genericRetailBrand(value:string|null|undefined){
  const b=norm(value);
  return b==="lulu pl"||b==="lulu"||b.startsWith("tamimi markets")||b==="fresh";
}
function candidateScore(product:any,text:string){
  const textTokens=new Set(tokens(text));
  const brandTokens=tokens(product.brand);
  const nameTokens=tokens([product.canonical_name_ar,product.canonical_name_en].filter(Boolean).join(" "));
  const genericBrand=genericRetailBrand(product.brand);
  const brand=overlap(brandTokens,textTokens);
  const name=overlap(nameTokens,textTokens);
  const observed=sizes(text);
  const size=sizeMatches(product,observed);
  const brandExact=brandTokens.length>0&&brand===1;
  const nameHits=nameTokens.filter(t=>textTokens.has(t)).length;

  if(!genericBrand&&brandTokens.length&&brand<0.5)return {score:0,brand,name,size,nameHits};
  if(nameHits<2&&!(brandExact&&nameHits>=1))return {score:0,brand,name,size,nameHits};
  if(product.net_content_value!=null&&observed.length&&size===0)return {score:0,brand,name,size,nameHits};

  const score=genericBrand
    ? (0.12*brand)+(0.63*name)+(0.25*size)
    : (brandTokens.length?0.4*brand:0.15)+(0.4*name)+(0.2*size)+(brandExact?0.05:0);
  return {score:Math.min(1,score),brand,name,size,nameHits};
}

Deno.serve(async(req:Request)=>{
  if(req.method!=="POST")return Response.json({error:"method_not_allowed"},{status:405});
  const projectUrl=Deno.env.get("SUPABASE_URL")??"",key=secretKey();
  if(!projectUrl||!key)return Response.json({error:"server_config_missing"},{status:503});
  let body:any={};try{body=await req.json()}catch{}
  const barcode=asciiDigits(String(body.barcode??"")).replace(/\D+/g,"").slice(0,18);
  const text=String(body.text??"").slice(0,MAX_TEXT_LENGTH).trim();
  if(text.length<4)return Response.json({status:"insufficient_text",payload:null});

  const db=createClient(projectUrl,key,{auth:{persistSession:false,autoRefreshToken:false}});
  const freshSince=new Date(Date.now()-FRESH_HOURS*3600_000).toISOString();
  const {data:activeRetailers,error:re}=await db.from("retailers")
    .select("id,slug,name_ar,name_en").eq("active",true);
  if(re)return Response.json({status:"backend_error",payload:null},{status:500});
  const retailers=(activeRetailers??[]).filter((r:any)=>ACTIVE_RETAILERS.has(String(r.slug)));
  const retailerIds=retailers.map((r:any)=>r.id);
  if(!retailerIds.length)return Response.json({status:"no_active_retailers",payload:null});

  const {data:currentPrices,error:pe}=await db.from("identity_price_periods")
    .select("canonical_product_id,retailer_id,branch_key,price,currency,source_url,last_seen_at")
    .in("retailer_id",retailerIds).is("valid_to",null).gte("last_seen_at",freshSince);
  if(pe)return Response.json({status:"backend_error",payload:null},{status:500});
  const prices=(currentPrices??[]).filter((p:any)=>Number(p.price)>0);
  const ids=[...new Set(prices.map((p:any)=>Number(p.canonical_product_id)).filter(Number.isFinite))];
  if(!ids.length)return Response.json({status:"no_fresh_catalog",payload:null});

  const {data:products,error:ce}=await db.from("canonical_products")
    .select("id,canonical_name_ar,canonical_name_en,brand,variant,net_content_value,net_content_unit,pack_count,image_url,product_info")
    .in("id",ids);
  if(ce)return Response.json({status:"backend_error",payload:null},{status:500});

  const scored=(products??[]).map((p:any)=>({product:p,...candidateScore(p,text)}))
    .filter((x:any)=>x.score>=0.72)
    .sort((a:any,b:any)=>b.score-a.score);
  if(!scored.length)return Response.json({status:"no_visual_match",payload:null});
  const best=scored[0],second=scored[1];
  if(second&&best.score-second.score<0.12)return Response.json({
    status:"ambiguous_visual_match",payload:null,
    candidates:scored.slice(0,3).map((x:any)=>({name:x.product.canonical_name_ar??x.product.canonical_name_en,brand:x.product.brand,confidence:x.score}))
  });

  const p=best.product;
  const retailerMap=new Map(retailers.map((r:any)=>[Number(r.id),r]));
  const offers=prices.filter((x:any)=>Number(x.canonical_product_id)===Number(p.id))
    .map((x:any)=>{
      const r:any=retailerMap.get(Number(x.retailer_id));
      return {
        retailer:r?.name_ar??r?.name_en??r?.slug??"متجر",
        price:Number(x.price),currency:x.currency??"SAR",updated_at:x.last_seen_at,
        branch_key:x.branch_key,source_url:x.source_url,barcode:null,match_method:"visual_text_identity"
      };
    }).sort((a:any,b:any)=>a.price-b.price);
  if(!offers.length)return Response.json({status:"no_current_offer",payload:null});
  const headline=offers[0];

  const thirtyDaysAgo=new Date(Date.now()-30*24*3600_000).toISOString();
  const {data:history}=await db.from("identity_price_periods").select("price")
    .eq("canonical_product_id",p.id).in("retailer_id",retailerIds).gte("valid_from",thirtyDaysAgo);
  const hist=(history??[]).map((x:any)=>Number(x.price)).filter((x:number)=>Number.isFinite(x)&&x>0);
  const productInfo=p.product_info&&typeof p.product_info==="object"
    ? {...p.product_info,data_source:[p.product_info.data_source,"visual_text_match"].filter(Boolean).join(" + ")}
    : {data_source:"visual_text_match"};

  return Response.json({
    status:"resolved_visual_match",
    confidence:best.score,
    payload:{
      barcode,
      canonical_product_id:Number(p.id),
      matched_barcodes:[],
      name_ar:p.canonical_name_ar,
      name_en:p.canonical_name_en,
      image_url:p.image_url,
      current_price:headline.price,
      currency:headline.currency,
      retailer:headline.retailer,
      price_updated_at:headline.updated_at,
      min_30d:hist.length?Math.min(...hist):headline.price,
      max_30d:hist.length?Math.max(...hist):headline.price,
      source_count:offers.length,
      confidence:best.score,
      offers,
      product_info:productInfo,
      exact_barcode_match:false,
      headline_match_method:"visual_text_identity"
    }
  });
});

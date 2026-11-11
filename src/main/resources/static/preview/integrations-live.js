// Google Maps + Toss Payments TEST. 비밀 키와 승인은 Java 서버에서만 처리합니다.
let runtimePromise,googlePromise,tossPromise;
function runtimeConfig(){return runtimePromise??=api('integrations/config').catch(e=>{runtimePromise=null;throw e})}
function externalScript(src){return new Promise((resolve,reject)=>{const s=document.createElement('script');s.src=src;s.async=true;s.onload=resolve;s.onerror=()=>reject(Error('외부 연결을 불러오지 못했습니다.'));document.head.append(s)})}
async function loadGoogle(key){if(window.google?.maps?.importLibrary)return;if(!googlePromise)googlePromise=new Promise((resolve,reject)=>{window.campusGoogleMapsReady=resolve;externalScript('https://maps.googleapis.com/maps/api/js?key='+encodeURIComponent(key)+'&loading=async&callback=campusGoogleMapsReady&language=ko&region=KR').catch(reject)});return googlePromise}
function loadToss(){if(window.TossPayments)return Promise.resolve();return tossPromise??=externalScript('https://js.tosspayments.com/v2/standard')}
function renderIntegration(){
 $('#section-kicker').textContent='INHA CAMPUS · SAFE TRADE';$('#section-title').textContent='가까운 곳에서, 안심하고 만나요.';$('#section-sub').textContent='거래 장소와 약속한 가격을 확인하세요.';
 const root=$('#results');root.className='integration-view';root.replaceChildren();const p=products.find(x=>x.id===tradeProductId)||products[0];if(!p){root.append(el('p','empty','상품을 먼저 선택해 주세요.'));return}tradeProductId=p.id;
 const select=el('select','trade-select');select.setAttribute('aria-label','거래할 상품');products.forEach(x=>select.append(new Option(x.title+' · '+statusText(x),x.id)));select.value=p.id;select.onchange=()=>{tradeProductId=Number(select.value);history.replaceState(null,'','#trade/'+tradeProductId);renderIntegration()};root.append(select);
 const cols=el('div','integration-grid'),mapPanel=el('article','integration-panel'),payPanel=el('article','integration-panel');cols.append(mapPanel,payPanel);root.append(cols);
 const saved=p.order?.latitude!=null?{name:p.order.meetingPlace,lat:Number(p.order.latitude),lng:Number(p.order.longitude)}:null;let selected=p.customPlace||saved||previewPlaces[p.place||0];
 mapPanel.append(el('span','eyebrow','MEETING POINT'),el('h3','','거래 장소 지정'));const mapNode=el('div','campus-map live-map'),placeSelect=el('select');placeSelect.setAttribute('aria-label','거래 장소');previewPlaces.forEach((x,i)=>placeSelect.append(new Option(x.name,i)));placeSelect.value=p.place||0;const chosen=el('p','chosen-place','📍 '+selected.name),link=el('a','map-link','Google 지도에서 위치 보기 ↗');link.target='_blank';link.rel='noopener';function updateLink(){link.href='https://www.google.com/maps/search/?api=1&query='+encodeURIComponent(selected.lat+','+selected.lng);chosen.textContent='📍 '+selected.name}updateLink();
 function embed(){mapNode.replaceChildren();const frame=el('iframe');frame.title=currentCampus.name+' 거래 장소 Google 지도';frame.loading='lazy';frame.referrerPolicy='no-referrer-when-downgrade';frame.src='https://maps.google.com/maps?q='+encodeURIComponent(selected.lat+','+selected.lng)+'&z=16&output=embed';mapNode.append(frame)}
 placeSelect.onchange=()=>{p.place=Number(placeSelect.value);p.customPlace=previewPlaces[p.place];renderIntegration()};
 mapPanel.append(mapNode,placeSelect,chosen);const save=actionButton(p.order?'이 장소로 약속 저장':'이 장소에서 약속 잡기',async()=>{if(!p.order){appointmentDialog(p,selected);return}save.disabled=true;const ok=await perform(()=>api('trades/'+p.order.orderId+'/meeting','PUT',{meetingPlace:selected.name,latitude:selected.lat,longitude:selected.lng,meetingTime:p.order.meetingTime}),'거래 장소를 저장했어요.',false);if(!ok)save.disabled=false},p.status==='done'||Boolean(p.order&&p.order.status!=='RESERVED'));
 mapPanel.append(save,link,el('p','fine-print',currentCampus.name+' 주변 지도입니다. 거래 장소를 선택하고 약속을 저장해 주세요.'));embed();
 runtimeConfig().then(async cfg=>{if(!cfg.mapsKey)return;await loadGoogle(cfg.mapsKey);if(!mapNode.isConnected)return;const {Map}=await google.maps.importLibrary('maps');mapNode.replaceChildren();const map=new Map(mapNode,{center:selected,zoom:16,mapId:'DEMO_MAP_ID',mapTypeControl:false,streetViewControl:false});const {AdvancedMarkerElement}=await google.maps.importLibrary('marker');const marker=new AdvancedMarkerElement({map,position:selected,title:selected.name});map.addListener('click',event=>{selected={name:'지도에서 선택한 장소 ('+event.latLng.lat().toFixed(5)+', '+event.latLng.lng().toFixed(5)+')',lat:event.latLng.lat(),lng:event.latLng.lng()};p.customPlace=selected;marker.position=selected;updateLink()})}).catch(()=>{if(mapNode.isConnected)embed()});
 payPanel.append(el('span','eyebrow','TOSS PAYMENTS'),el('h3','','거래 · 결제'),el('p','payment-product',p.title),el('strong','payment-amount',formatMoney(displayPrice(p))));if(p.order){payPanel.append(el('p','locked-price','약속한 거래 가격 유지'),el('p','fine-print','주문번호 '+p.order.orderId));}
 if(p.order) renderTossEscrowPanel(payPanel,p.order);
 if(p.status==='done'){payPanel.append(el('p','locked-price','✓ 거래 완료'));if(p.order)renderTradeRating(payPanel,p.order.orderId);return}
 if(!p.order){payPanel.append(el('p','fine-print','먼저 약속을 확정하면 약속한 가격으로 결제할 수 있어요.'),actionButton('거래 약속 잡기',()=>appointmentDialog(p)));return}
 if(p.order.buyer===actorKey()) renderPaymentControls(payPanel,p);
 else if(p.order.status==='PAID') payPanel.append(el('p','locked-price','결제 승인 완료'));
 if(p.order.status==='RESERVED')payPanel.append(actionButton('예약 취소',()=>cancelReservation(p),false,'secondary wide'));
 if(['RESERVED','PAID'].includes(p.order.status))payPanel.append(actionButton('물건 전달 · 거래 완료',()=>completeTrade(p),false,'secondary wide'));
 payPanel.append(el('p','fine-print','대면 거래 완료와 토스 결제 승인은 별도로 기록해요. 실제 송금·정산은 지원하지 않습니다.'));
}
window.gm_authFailure=()=>{document.querySelectorAll('.live-map').forEach(n=>{n.replaceChildren(el('p','integration-error','Google 지도 인증을 확인해 주세요. 아래 Google 지도 링크로 위치를 확인할 수 있어요.'))})};

async function renderPaymentControls(panel,product){
 const order=product.order, message=el('p','sdk-status','토스 결제 상태를 확인하고 있습니다.');
 panel.append(message);
 try {
  const [cfg,receipt]=await Promise.all([runtimeConfig(),api('payments/'+encodeURIComponent(order.orderId)+'/status')]);
  if(receipt.status!=='UNCONFIRMED'){
   const labels={DONE:'결제 승인',PARTIAL_CANCELED:'부분 취소',CANCELED:'결제 취소',ABORTED:'결제 실패',EXPIRED:'결제 만료'};
   message.textContent=(receipt.paymentMode==='TEST'?'테스트 결제 · ':'실제 결제 · ')+(labels[receipt.status]||receipt.status);
   if(receipt.status==='PARTIAL_CANCELED') message.textContent+=' · 남은 금액 '+formatMoney(receipt.balanceAmount);
   return;
  }
  if(!['RESERVED','PAYMENT_READY'].includes(order.status)||order.agreedPrice===0){
   message.textContent=order.status==='PAID'?'결제 승인 완료':'온라인 결제가 필요하지 않습니다.';return;
  }
  if(!cfg.tossReady){message.textContent='토스 결제 키가 설정되지 않아 결제할 수 없습니다.';return;}
  const live=cfg.paymentMode==='LIVE';
  message.textContent=live?'운영 결제입니다. 승인하면 실제 카드에 청구됩니다.':'테스트 결제입니다. 실제 금액은 청구되지 않습니다.';
  let chooseEscrow,escrowEmail;
  if(cfg.tossEscrowTestReady && !live){
   const choice=el('label','fine-print');chooseEscrow=document.createElement('input');chooseEscrow.type='checkbox';
   chooseEscrow.checked=Boolean(receipt.useEscrow);chooseEscrow.disabled=chooseEscrow.checked;
   choice.append(chooseEscrow,document.createTextNode(' 토스 테스트 에스크로로 직거래하기'));panel.append(choice);
   escrowEmail=document.createElement('input');escrowEmail.type='email';escrowEmail.required=true;
   escrowEmail.placeholder='토스 구매확정 메일을 받을 이메일';escrowEmail.setAttribute('aria-label',escrowEmail.placeholder);
   escrowEmail.hidden=!chooseEscrow.checked;chooseEscrow.onchange=()=>{escrowEmail.hidden=!chooseEscrow.checked;};panel.append(escrowEmail);
  }
  const button=actionButton(live?'실제 카드 결제하기':'테스트 카드 결제하기',async()=>{
   button.disabled=true;
   try {
    if(chooseEscrow?.checked&&!escrowEmail.reportValidity())return;
    const request=await api('payments/prepare','POST',{orderId:order.orderId,useEscrow:Boolean(chooseEscrow?.checked)});
    if(request.paymentMode!==cfg.paymentMode)throw Error('결제 모드가 변경되었습니다. 새로고침해 주세요.');
    await loadToss();
    const card={flowMode:'DEFAULT'};
    if(request.useEscrow)card.useEscrow=true;
    await TossPayments(cfg.tossClientKey).payment({customerKey:request.customerKey}).requestPayment({
     method:'CARD',amount:{currency:'KRW',value:request.amount},orderId:request.orderId,orderName:request.orderName,
     ...(request.useEscrow?{customerEmail:escrowEmail.value.trim()}:{}),
     successUrl:location.origin+'/preview/payment-result.html',failUrl:location.origin+'/preview/payment-result.html?failed=1',
     card
    });
   }catch(e){toast(e.message||'결제창을 닫았어요.');}
   finally{button.disabled=false;await loadData();}
  });
  panel.append(button);
 }catch(e){message.textContent=e.message||'결제 상태를 확인할 수 없습니다.';}
}

async function renderTossEscrowPanel(panel,order){
 const path='/api/trades/'+encodeURIComponent(order.orderId)+'/toss-escrow';
 const block=el('section','integration-panel');
 const buyer=order.buyer===actorKey();
 async function read(res){
  const data=await res.json();
  if(!res.ok)throw Error(data.detail||data.message||'에스크로 정보를 확인할 수 없습니다.');
  return data;
 }
 function show(page){
  const state=page.escrow;
  if(!state.selected){renderEscrowSimulation(panel,order);return;}
  if(!block.isConnected)panel.append(block);
  const labels={SELECTED:'테스트 결제 승인 대기',NOT_APPLIED:'이 결제에 토스 에스크로가 적용되지 않았습니다.',
   AWAITING_LOOKUP:'결제 승인 완료 · 토스 거래 조회 필요',IN_PROGRESS:'토스에서 구매확정 대기',
   CONFIRMED:'토스 구매확정 완료',CANCEL_REQUESTED:'토스에 취소 요청됨',CANCELED:'토스 에스크로 취소 완료'};
  block.replaceChildren(el('h3','','토스 테스트 에스크로'),el('p','locked-price',labels[state.status]||state.status));
  if(state.status==='NOT_APPLIED'||state.status==='SELECTED')return;
  async function send(action,body){
   const response=await fetch(path+'/'+action,{method:'POST',headers:{'X-CSRF-Token':page.csrfToken,'Content-Type':'application/json'},
    ...(body?{body:JSON.stringify(body)}:{})});
   show(await read(response));
  }
  function addAction(label,action,input){
   const btn=actionButton(label,async()=>{
    btn.disabled=true;
    try{await send(action,input?.());}catch(e){toast(e.message);btn.disabled=false;}
   },false,'secondary wide');
   block.append(btn);
  }
  addAction('토스에서 상태 조회','refresh');
  if(state.delivered)block.append(el('p','fine-print','직접 전달이 등록되었습니다. 구매확정은 구매자가 토스 안내 메일에서 진행합니다.'));
  if(state.status==='IN_PROGRESS' && !state.delivered){
   if(!state.received && buyer){
    const name=document.createElement('input');name.maxLength=50;name.placeholder='실제 수령인 이름';name.setAttribute('aria-label','수령인 이름');
    block.append(el('p','fine-print','물품을 직접 받은 뒤 이름을 입력하세요. 판매자가 등록하면 이 이름과 수령 시각을 토스에 보냅니다.'),name);
    addAction('물품 수령 기록','receive',()=>({receiverName:name.value}));
   }else if(state.received && !buyer){addAction('토스에 직접 전달 등록','deliver');}
   else {block.append(el('p','fine-print',state.received?'판매자의 직접 전달 등록을 기다립니다.':'구매자의 물품 수령 기록을 기다립니다.'));}
  }
  if(state.status==='CONFIRMED'&&!buyer && typeof window.renderTossPayoutStatus==='function')
   window.renderTossPayoutStatus(block,order);
 }
 try {show(await read(await fetch(path,{cache:'no-store'})));}
 catch(e){block.append(el('p','fine-print',e.message));panel.append(block);}
}

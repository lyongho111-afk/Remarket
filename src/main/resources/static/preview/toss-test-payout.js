window.renderTossPayoutStatus = async function (panel, order) {
  const box = document.createElement('section');
  const title = document.createElement('h4');
  title.textContent = '토스 테스트 지급 내역';
  const message = document.createElement('p');
  message.className = 'fine-print';
  box.append(title, message);
  panel.append(box);
  const url = '/api/trades/' + encodeURIComponent(order.orderId) + '/test-payout';
  const labels = {REQUESTED:'요청됨', IN_PROGRESS:'처리 중', COMPLETED:'테스트 지급 완료',
    FAILED:'실패', CANCELED:'취소', DELETED:'삭제', REJECTED:'반려'};
  let csrf;
  async function fetchState(refresh) {
    const response = await fetch(url + (refresh ? '/refresh' : ''), refresh ?
      {method:'POST', headers:{'X-CSRF-Token':csrf}} : {cache:'no-store'});
    const data = await response.json();
    if (!response.ok) throw Error(data.message || data.detail || '지급 상태를 조회하지 못했습니다.');
    csrf = data.csrfToken;
    const p = data.payout;
    message.textContent = p.linked ? labels[p.status] + ' · ' + p.amount.toLocaleString('ko-KR') +
        '원 · 마지막 조회 ' + new Date(p.verifiedAt).toLocaleString('ko-KR') :
      '관리자가 이 주문과 외부 법인 테스트 셀러의 지급 내역을 연결하면 조회할 수 있어요.';
    return p.linked;
  }
  try {
    if (!await fetchState(false) || !box.isConnected) return;
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'secondary'; button.textContent = '토스에서 다시 조회';
    button.onclick = async () => {
      button.disabled = true;
      try { await fetchState(true); } catch (e) { message.textContent=e.message; }
      finally { button.disabled=false; }
    };
    box.append(button);
  } catch (e) { message.textContent = e.message; }
};

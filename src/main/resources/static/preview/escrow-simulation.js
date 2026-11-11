// This panel only displays the server's simulated ledger for Toss test receipts.
async function renderEscrowSimulation(panel, order) {
  const section = el('section', 'integration-panel');
  const heading = el('h3', '', '에스크로 · 모의 정산');
  section.append(heading);
  panel.append(section);
  const url = '/api/trades/' + encodeURIComponent(order.orderId) + '/escrow-simulation';

  async function read(response) {
    const data = await response.json();
    if (!response.ok) throw Error(data.message || data.detail || '정산 상태를 확인하지 못했습니다.');
    return data;
  }

  const labels = {
    UNFUNDED: '승인 대기',
    HELD: '테스트 결제금 보관 중',
    CONFIRMED: '구매 확정 · 정산 대기',
    SETTLED: '판매자 모의 정산 완료',
    PARTIALLY_REFUNDED: '부분 취소 · 추가 정산 중지',
    REFUNDED: '전액 취소'
  };

  function show(page) {
    if (!section.isConnected) return;
    const ledger = page.ledger;
    section.replaceChildren(heading, el('p', 'locked-price', labels[ledger.state] || '상태 확인 중'));
    if (ledger.state === 'UNFUNDED') {
      section.append(el('p', 'fine-print', '토스 테스트 결제가 승인되면 모의 보관 금액을 확인할 수 있어요.'));
      return;
    }
    section.append(el('p', 'fine-print', '테스트 결제 ' + formatMoney(ledger.amount) + ' · 취소 반영 잔액 ' + formatMoney(ledger.balance)));
    if (ledger.settledAt) {
      section.append(el('p', 'fine-print', '판매자 모의 정산 잔액 ' + formatMoney(ledger.balance)));
    }
    section.append(el('p', 'fine-print', '시연용 장부이며 실제 계좌로 송금되지 않습니다.'));
    const buyer = order.buyer === actorKey();
    const action = buyer && ledger.state === 'HELD' ? 'confirm' :
        (!buyer && ledger.state === 'CONFIRMED' ? 'settle' : null);
    if (!action) return;
    const button = actionButton(action === 'confirm' ? '물품 수령 · 구매 확정' : '판매자 모의 정산 기록', async () => {
      if (button.disabled) return;
      button.disabled = true;
      try {
        const res = await fetch(url + '/' + action, {
          method: 'POST', headers: {'X-CSRF-Token': page.csrfToken}
        });
        const next = await read(res);
        show(next);
        await loadData();
      } catch (error) {
        toast(error.message);
        button.disabled = false;
      }
    }, false, 'secondary wide');
    section.append(button);
  }

  try { show(await read(await fetch(url, {cache:'no-store'}))); }
  catch (error) { if (section.isConnected) section.append(el('p', 'fine-print', error.message)); }
}

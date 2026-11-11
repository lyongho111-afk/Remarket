(async function () {
  const status = document.querySelector('#session');
  const root = '/api/admin/test-payouts/';
  async function request(path, config = {}) {
    const response = await fetch(root + path, {cache:'no-store', ...config});
    const data = await response.json();
    if (!response.ok) throw Error(data.message || data.detail || '요청을 처리할 수 없습니다.');
    return data;
  }
  try {
    const session = await request('session');
    status.textContent = session.enabled ? '테스트 지급 조회가 활성화되어 있습니다.' :
      '서버에 계약 상점의 테스트 지급 조회 키와 활성화 설정이 필요합니다.';
    if (!session.enabled) return;
    for (const form of document.querySelectorAll('form')) {
      const button = form.querySelector('button'), output = form.querySelector('output');
      button.disabled = false;
      form.onsubmit = async e => {
        e.preventDefault();
        if (button.disabled) return;
        button.disabled = true;
        output.textContent = '토스에서 확인 중…';
        try {
          const body = Object.fromEntries(new FormData(form));
          if (form.id === 'seller') body.memberId = Number(body.memberId);
          const result = await request(form.id,{method:'POST',headers:{
            'Content-Type':'application/json','X-CSRF-Token':session.csrfToken},body:JSON.stringify(body)});
          output.textContent = form.id === 'seller' ? '법인 테스트 셀러를 확인하고 연결했습니다.' :
            '토스 지급 상태 ' + result.status + ' · ' + result.amount.toLocaleString('ko-KR') + '원';
        } catch (err) { output.textContent = err.message; }
        finally { button.disabled = false; }
      };
    }
  } catch(err) { status.textContent = err.message + ' 관리자 홈에서 로그인 상태를 확인하세요.'; }
})();

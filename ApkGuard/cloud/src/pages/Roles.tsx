import { useEffect, useState, type FormEvent } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, apiPost, type Operator, type Role } from '../lib/api';
import { useToast } from '../components/Toast';
import { Empty, Panel, PanelHead, Spinner, Tag } from '../components/ui';
import { agoSafe, COMP_UZ, compUz, PERM_UZ, permUz } from '../lib/format';

// ── Soatlik maxfiy kod (maxfiy kirishning 2-qulfi) ──────────────────────────
function CodeBox() {
  const { data, reload } = usePoll(() => apiGet<{ code: string; seconds_left: number }>('/api/role/code'), 0);
  const [left, setLeft] = useState(0);
  const { show } = useToast();

  useEffect(() => { if (data) setLeft(data.seconds_left || 0); }, [data]);
  useEffect(() => {
    const id = window.setInterval(() => {
      setLeft((l) => {
        if (l <= 1) { reload(); return 0; }
        return l - 1;
      });
    }, 1000);
    return () => window.clearInterval(id);
  }, [reload]);

  const code = data?.code || '······';
  const mm = String(Math.floor(left / 60)).padStart(2, '0');
  const ss = String(left % 60).padStart(2, '0');
  const pct = Math.max(0, Math.min(100, (left / 3600) * 100));

  return (
    <Panel>
      <PanelHead sub="2-qulf · har soatda yangilanadi" title="Joriy maxfiy kod" />
      <div className="body-pad">
        <div className="code-box">
          <div
            className={'code-val' + (data ? '' : ' dim')}
            onClick={() => { if (data?.code) { navigator.clipboard?.writeText(data.code); show('Kod nusxalandi'); } }}
            style={{ cursor: data?.code ? 'pointer' : 'default' }}
            title="Nusxalash uchun bosing"
          >
            {code}
          </div>
          <div className="code-meta">{data ? `Yangilanishiga ${mm}:${ss}` : 'yuklanmoqda…'}</div>
          <div className="code-bar"><i style={{ width: `${pct}%` }} /></div>
        </div>
      </div>
    </Panel>
  );
}

export default function Roles() {
  const { data, loading, reload } = usePoll(() => apiGet<{ roles: Role[]; operators: Operator[] }>('/api/role/admin'), 30000);
  const { show } = useToast();
  const roles = data?.roles || [];
  const operators = data?.operators || [];

  // create role
  const [rname, setRname] = useState('');
  const [perms, setPerms] = useState<string[]>([]);
  const [comps, setComps] = useState<string[]>([]);
  const [savingRole, setSavingRole] = useState(false);

  // create operator
  const [login, setLogin] = useState('');
  const [pass, setPass] = useState('');
  const [roleName, setRoleName] = useState('');
  const [savingOp, setSavingOp] = useState(false);

  const flip = (arr: string[], set: (v: string[]) => void, v: string) =>
    set(arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

  const createRole = async (e: FormEvent) => {
    e.preventDefault();
    if (!rname.trim()) return;
    setSavingRole(true);
    try {
      await apiPost('/api/role/admin', { action: 'create_role', name: rname.trim(), permissions: perms, components: comps });
      show('Rol saqlandi');
      setRname(''); setPerms([]); setComps([]);
      reload();
    } catch (ex) { show((ex as Error).message || 'Xatolik'); }
    finally { setSavingRole(false); }
  };

  const createOp = async (e: FormEvent) => {
    e.preventDefault();
    if (!login.trim() || !pass) return;
    setSavingOp(true);
    try {
      await apiPost('/api/role/admin', { action: 'create_operator', login: login.trim(), password: pass, role_name: roleName });
      show('Operator qo‘shildi');
      setLogin(''); setPass(''); setRoleName('');
      reload();
    } catch (ex) { show((ex as Error).message || 'Xatolik'); }
    finally { setSavingOp(false); }
  };

  const toggleActive = async (op: Operator) => {
    try {
      await apiPost('/api/role/admin', { action: 'set_active', login: op.login, active: !op.active });
      reload();
    } catch (ex) { show((ex as Error).message || 'Xatolik'); }
  };

  return (
    <>
      <div className="page-intro">
        <h1>Rollar va operatorlar</h1>
        <p>Maxfiy kirish 4 qulfdan iborat: qurilma siri (APK) → soatlik kod → login → parol. Bu yerda rollar va kirish huquqlari boshqariladi.</p>
      </div>

      <div className="note" style={{ marginBottom: 16 }}>
        <span className="ni">🔐</span>
        <span>
          Operator faqat haqiqiy KiberQalqon ilovasidan, joriy soatlik kod bilan kira oladi. Har bir urinish (muvaffaqiyatli yoki yo‘q) audit jurnaliga yoziladi.
        </span>
      </div>

      <div className="grid map-grid">
        <div className="grid">
          <Panel>
            <PanelHead sub="Yangi rol" title="Rol yaratish" />
            <form className="body-pad" onSubmit={createRole}>
              <div className="field">
                <span className="fld-lbl">Rol nomi</span>
                <input value={rname} onChange={(e) => setRname(e.target.value)} placeholder="masalan: Tahlilchi" />
              </div>
              <div className="field">
                <span className="fld-lbl">Huquqlar</span>
                <div className="pick">
                  {Object.keys(PERM_UZ).map((p) => (
                    <label key={p}>
                      <input type="checkbox" checked={perms.includes(p)} onChange={() => flip(perms, setPerms, p)} />
                      {permUz(p)}
                    </label>
                  ))}
                </div>
              </div>
              <div className="field">
                <span className="fld-lbl">Ko‘rinadigan bo‘limlar</span>
                <div className="pick">
                  {Object.keys(COMP_UZ).map((c) => (
                    <label key={c}>
                      <input type="checkbox" checked={comps.includes(c)} onChange={() => flip(comps, setComps, c)} />
                      {compUz(c)}
                    </label>
                  ))}
                </div>
              </div>
              <button className="btn block" disabled={savingRole || !rname.trim()}>
                {savingRole ? <span className="spinner" /> : 'Rolni saqlash'}
              </button>
            </form>
          </Panel>

          <Panel>
            <PanelHead sub="Yangi operator" title="Operator qo‘shish" />
            <form className="body-pad" onSubmit={createOp}>
              <div className="field">
                <span className="fld-lbl">Login</span>
                <input value={login} onChange={(e) => setLogin(e.target.value)} placeholder="login" autoComplete="off" />
              </div>
              <div className="field">
                <span className="fld-lbl">Parol</span>
                <input type="password" value={pass} onChange={(e) => setPass(e.target.value)} placeholder="kuchli parol" autoComplete="new-password" />
              </div>
              <div className="field">
                <span className="fld-lbl">Rol</span>
                <select value={roleName} onChange={(e) => setRoleName(e.target.value)}>
                  <option value="">— rol tanlang —</option>
                  {roles.map((r) => <option key={r.name} value={r.name}>{r.name}</option>)}
                </select>
              </div>
              <button className="btn block" disabled={savingOp || !login.trim() || !pass}>
                {savingOp ? <span className="spinner" /> : 'Operatorni qo‘shish'}
              </button>
            </form>
          </Panel>
        </div>

        <div className="grid">
          <CodeBox />

          <Panel>
            <PanelHead sub="Mavjud rollar" title={`${roles.length} ta rol`} />
            <div className="body-pad">
              {loading && !roles.length ? (
                <Spinner label="Yuklanmoqda…" />
              ) : !roles.length ? (
                <Empty>Hali rol yaratilmagan</Empty>
              ) : (
                <div className="adm-list">
                  {roles.map((r) => (
                    <div className="role-row" key={r.id || r.name}>
                      <b>{r.name}</b>
                      <div className="tags">
                        {(r.permissions || []).map((p) => <Tag kind="perm" key={p}>{permUz(p)}</Tag>)}
                        {(r.components || []).map((c) => <Tag kind="comp" key={c}>{compUz(c)}</Tag>)}
                        {!(r.permissions || []).length && !(r.components || []).length && (
                          <span style={{ color: 'var(--ink-3)', fontSize: 12 }}>huquq berilmagan</span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </Panel>

          <Panel>
            <PanelHead sub="Operatorlar" title={`${operators.length} ta operator`} />
            <div className="body-pad">
              {!operators.length ? (
                <Empty>Operator yo‘q</Empty>
              ) : (
                <div className="adm-list">
                  {operators.map((op) => (
                    <div className="op-row" key={op.id || op.login}>
                      <div className="op-main">
                        <div className="op-login">{op.login}</div>
                        <div className="op-sub">
                          {(op.role_name || 'rolsiz') + ' · ' + (op.last_login_at ? 'oxirgi: ' + agoSafe(op.last_login_at) : 'hech kirmagan')}
                        </div>
                      </div>
                      <button
                        className={'op-toggle ' + (op.active ? 'on' : 'off')}
                        onClick={() => toggleActive(op)}
                      >
                        {op.active ? 'Faol' : 'O‘chiq'}
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </Panel>
        </div>
      </div>
    </>
  );
}

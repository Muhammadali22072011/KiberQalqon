import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, apiPost, type NewsItem } from '../lib/api';
import { useToast } from '../components/Toast';
import { Empty, Panel, PanelHead, Spinner } from '../components/ui';
import { uzDateSafe } from '../lib/format';
import { useAuth } from '../context/AuthContext';

const LEVELS: { v: string; label: string }[] = [
  { v: 'info', label: 'Oddiy' },
  { v: 'warning', label: 'Ogohlantirish' },
  { v: 'critical', label: 'Kritik' },
];

const fileToDataUrl = (f: File) =>
  new Promise<string>((resolve, reject) => {
    const fr = new FileReader();
    fr.onload = () => resolve(String(fr.result));
    fr.onerror = () => reject(new Error('faylni o‘qib bo‘lmadi'));
    fr.readAsDataURL(f);
  });

export default function News() {
  const { isOwner } = useAuth();
  const canManage = true; // egasi va admin — ikkalasi ham e'lon JOYLAYDI
  // Profil sahifasi "o'chirish/o'zgartirish faqat egada" deb va'da beradi — shunga mos: pin/o'chirish FAQAT egasi.
  const canEdit = isOwner;
  const { data, loading, reload } = usePoll(() => apiGet<{ news: NewsItem[] }>('/api/news'), 20000);
  const { show } = useToast();
  const news = data?.news || [];

  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [level, setLevel] = useState('info');
  const [imgUrl, setImgUrl] = useState('');
  const [uploading, setUploading] = useState(false);
  const [saving, setSaving] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  // Sahifaga kirilganda — hammasini "o‘qilgan" deb belgilaymiz (sidebar belgisi tushadi).
  useEffect(() => { localStorage.setItem('kq_news_seen', String(Date.now())); }, []);

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!/^image\/(png|jpe?g|gif|webp)$/i.test(file.type)) { show('Faqat png/jpg/gif/webp'); return; }
    if (file.size > 3_000_000) { show('Rasm 3MB dan katta'); return; }
    setUploading(true);
    try {
      const dataUrl = await fileToDataUrl(file);
      const r = await apiPost<{ url: string }>('/api/news', { action: 'upload_image', data: dataUrl });
      setImgUrl(r.url);
      show('Rasm yuklandi');
    } catch (ex) {
      show((ex as Error).message || 'Yuklab bo‘lmadi');
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const create = async (e: FormEvent) => {
    e.preventDefault();
    if (!title.trim()) return;
    // Rasm havolasi http(s) bo'lmasa server uni jim tashlab yuboradi (e'lon rasmsiz chiqadi).
    // Foydalanuvchiga oldindan aytamiz — "joyladim, rasm yo'q" sirli holatini oldini olamiz.
    const img = imgUrl.trim();
    // Telefon faqat https rasmni ko'rsatadi (cleartext bloklangan) — toast aytgani bilan bir xil
    // bo'lishi uchun bu yerda ham FAQAT https'ni qabul qilamiz (server ham http'ni rad etadi).
    if (img && !/^https:\/\//i.test(img)) {
      show("Rasm havolasi noto‘g‘ri — https:// bilan boshlanishi kerak");
      return;
    }
    setSaving(true);
    try {
      await apiPost('/api/news', {
        action: 'create',
        title: title.trim(),
        body: body.trim(),
        level,
        image_url: img,
      });
      show('E‘lon joylandi');
      setTitle(''); setBody(''); setLevel('info'); setImgUrl('');
      reload();
    } catch (ex) {
      show((ex as Error).message || 'Xatolik');
    } finally {
      setSaving(false);
    }
  };

  const togglePin = async (n: NewsItem) => {
    try { await apiPost('/api/news', { action: 'toggle_pin', id: n.id, pinned: !n.pinned }); reload(); }
    catch (ex) { show((ex as Error).message || 'Xatolik'); }
  };

  const del = async (n: NewsItem) => {
    if (!window.confirm('E‘lon o‘chirilsinmi?')) return;
    try { await apiPost('/api/news', { action: 'delete', id: n.id }); show('O‘chirildi'); reload(); }
    catch (ex) { show((ex as Error).message || 'Xatolik'); }
  };

  return (
    <>
      <div className="page-intro">
        <h1>Yangiliklar va e‘lonlar</h1>
        <p>
          {canManage
            ? 'Bu yerga joylangan e‘lonlar darhol foydalanuvchilarning Anor Qalqon ilovasi bosh ekranida ko‘rinadi.'
            : 'Rahbariyat e‘lonlari. Bu yerda faqat o‘qiy olasiz — e‘lon joylash huquqi egada.'}
        </p>
      </div>

      <div className={canManage ? 'grid map-grid' : 'grid'}>
        {canManage && (
        <Panel>
          <PanelHead sub="Yangi e‘lon" title="E‘lon yozish" />
          <form className="body-pad" onSubmit={create}>
            <div className="field">
              <span className="fld-lbl">Sarlavha</span>
              <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="E‘lon sarlavhasi" maxLength={140} />
            </div>
            <div className="field">
              <span className="fld-lbl">Matn</span>
              <textarea value={body} onChange={(e) => setBody(e.target.value)} placeholder="To‘liq matn (ixtiyoriy)" rows={4} />
            </div>
            <div className="field">
              <span className="fld-lbl">Darajasi</span>
              <select value={level} onChange={(e) => setLevel(e.target.value)}>
                {LEVELS.map((l) => <option key={l.v} value={l.v}>{l.label}</option>)}
              </select>
            </div>
            <div className="field">
              <span className="fld-lbl">Rasm (ixtiyoriy)</span>
              <div className="uploader">
                {imgUrl && <img className="up-prev" src={imgUrl} alt="" />}
                <label className="up-btn">
                  {uploading ? <span className="spinner" /> : '📷'} {imgUrl ? 'Boshqa rasm' : 'Rasm yuklash'}
                  <input
                    ref={fileRef}
                    type="file"
                    accept="image/png,image/jpeg,image/gif,image/webp"
                    onChange={onFile}
                    style={{ display: 'none' }}
                  />
                </label>
                {imgUrl && (
                  <button type="button" className="btn ghost" onClick={() => setImgUrl('')}>Olib tashlash</button>
                )}
              </div>
              <input
                value={imgUrl}
                onChange={(e) => setImgUrl(e.target.value)}
                placeholder="yoki rasm havolasini joylang (https://…)"
                style={{ marginTop: 8 }}
              />
            </div>
            <button className="btn block" disabled={saving || uploading || !title.trim()}>
              {saving ? <span className="spinner" /> : 'E‘lonni joylash'}
            </button>
          </form>
        </Panel>
        )}

        <Panel>
          <PanelHead sub="Lenta" title={`${news.length} ta e‘lon`} />
          <div className="body-pad">
            {loading && !news.length ? (
              <Spinner label="Yuklanmoqda…" />
            ) : !news.length ? (
              <Empty>Hali e‘lon yo‘q</Empty>
            ) : (
              <div className="news-list">
                {news.map((n) => (
                  <article className={'news-card lv-' + (n.level || 'info')} key={n.id}>
                    <div className="nc-top">
                      {n.image_url && <img className="nc-thumb" src={n.image_url} alt="" />}
                      <div className="nc-title">{n.title}</div>
                      <span className="nc-date">{uzDateSafe(n.created_at)}</span>
                      {canEdit && (<>
                        <button className={'nc-act' + (n.pinned ? ' on' : '')} title="Qadab qo‘yish" onClick={() => togglePin(n)}>📌</button>
                        <button className="nc-act" title="O‘chirish" onClick={() => del(n)}>🗑</button>
                      </>)}
                    </div>
                    {n.body && <div className="nc-body">{n.body}</div>}
                    {n.image_url && <img className="nc-img" src={n.image_url} alt="" />}
                  </article>
                ))}
              </div>
            )}
          </div>
        </Panel>
      </div>
    </>
  );
}

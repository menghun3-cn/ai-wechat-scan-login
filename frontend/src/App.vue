<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'

const QR_IMAGE = '/wechat-qrcode.jpg'

const state = ref('loading') // loading | ready | confirmed | home | expired | error
const message = ref('')
const secondsLeft = ref(0)
const openid = ref('')
const loginTime = ref('')
let loginId = ''
let pollTimer = null
let countdownTimer = null

const login = reactive({ code: '' })

function stopTimers() {
  if (pollTimer) clearInterval(pollTimer)
  if (countdownTimer) clearInterval(countdownTimer)
  pollTimer = null
  countdownTimer = null
}

async function createLogin() {
  state.value = 'loading'
  message.value = ''
  try {
    const res = await fetch('/api/auth/wxlogin/create', { method: 'POST' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data = await res.json()
    loginId = data.loginId
    login.code = data.code
    secondsLeft.value = Math.max(0, Math.round((data.expiresAt - Date.now()) / 1000))
    state.value = 'ready'
    startTimers()
  } catch {
    state.value = 'error'
    message.value = '无法连接登录服务，请稍后重试'
  }
}

function startTimers() {
  stopTimers()
  pollTimer = setInterval(pollStatus, 2000)
  countdownTimer = setInterval(() => {
    if (secondsLeft.value > 0) secondsLeft.value--
    if (secondsLeft.value <= 0 && state.value === 'ready') {
      state.value = 'expired'
      message.value = '短码已过期，请点击刷新重新获取'
      stopTimers()
    }
  }, 1000)
}

async function pollStatus() {
  if (!loginId || state.value !== 'ready') return
  try {
    const res = await fetch(`/api/auth/wxlogin/status?loginId=${encodeURIComponent(loginId)}`)
    if (!res.ok) return
    const data = await res.json()
    if (data.status === 'confirmed' && data.token) {
      sessionStorage.setItem('access_token', data.token)
      if (data.openid) sessionStorage.setItem('openid', data.openid)
      openid.value = data.openid || sessionStorage.getItem('openid') || ''
      stopTimers()
      state.value = 'confirmed'
      message.value = '登录成功，正在进入系统…'
      loginTime.value = new Date().toLocaleString('zh-CN', { hour12: false })
      // 短暂展示成功态后进入首页
      setTimeout(() => {
        if (state.value === 'confirmed') state.value = 'home'
      }, 1000)
    } else if (data.status === 'expired') {
      stopTimers()
      state.value = 'expired'
      message.value = '短码已过期，请点击刷新重新获取'
    }
  } catch {
    /* 网络抖动静默重试 */
  }
}

function logout() {
  sessionStorage.removeItem('access_token')
  sessionStorage.removeItem('openid')
  openid.value = ''
  loginTime.value = ''
  message.value = ''
  createLogin()
}

function maskOpenid(id) {
  if (!id) return '匿名用户'
  return id.length > 10 ? id.slice(0, 6) + '****' + id.slice(-4) : id
}

function formatCode(c) {
  return c.replace(/(\d{3})(\d{3})/, '$1 $2')
}

function formatCountdown(s) {
  const m = Math.floor(s / 60)
  const ss = s % 60
  return `${m}:${String(ss).padStart(2, '0')}`
}

onMounted(() => {
  // 已有登录态则直接进入首页
  if (sessionStorage.getItem('access_token')) {
    openid.value = sessionStorage.getItem('openid') || ''
    state.value = 'home'
  } else {
    createLogin()
  }
})
onBeforeUnmount(stopTimers)
</script>

<template>
  <!-- 登录视图 -->
  <div v-if="state !== 'home'" class="card">
    <div class="card-header">
      <div class="logo">💬</div>
      <h1>微信扫码登录</h1>
      <p class="subtitle">使用微信扫一扫关注公众号，发送短码即可登录</p>
    </div>

    <div class="qr-wrap">
      <img v-if="state !== 'loading' && state !== 'error'" :src="QR_IMAGE" alt="公众号二维码" class="qr" />
      <div v-else class="qr-placeholder">{{ state === 'loading' ? '加载中…' : '二维码加载失败' }}</div>
      <div v-if="state === 'confirmed'" class="qr-mask confirmed-mask">
        <div class="mask-icon">✓</div>
        <div>登录成功</div>
      </div>
    </div>

    <div class="code-box" :class="{ disabled: state !== 'ready' }">
      <span class="code-label">回复短码</span>
      <span class="code-value">{{ state === 'ready' ? formatCode(login.code) : '· · · · · ·' }}</span>
      <span v-if="state === 'ready'" class="countdown">{{ formatCountdown(secondsLeft) }}</span>
    </div>

    <ol class="steps">
      <li>打开微信，扫描左侧二维码关注公众号</li>
      <li>在公众号对话框发送上方 <b>6 位数字短码</b></li>
      <li>公众号回复“登录成功”后，本页将自动进入系统</li>
    </ol>

    <p v-if="message" class="message" :class="state">{{ message }}</p>

    <button v-if="state === 'expired' || state === 'error'" class="refresh-btn" @click="createLogin">
      刷新重试
    </button>
  </div>

  <!-- 首页（登录成功后） -->
  <div v-else class="home">
    <div class="home-card">
      <div class="avatar">✓</div>
      <h1>欢迎回来</h1>
      <p class="home-sub">您已通过微信扫码成功登录</p>

      <div class="info-list">
        <div class="info-item">
          <span class="info-label">用户标识</span>
          <span class="info-value" :title="openid">{{ maskOpenid(openid) }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">登录方式</span>
          <span class="info-value">微信扫码 / 短码</span>
        </div>
        <div class="info-item">
          <span class="info-label">登录时间</span>
          <span class="info-value">{{ loginTime }}</span>
        </div>
      </div>

      <button class="logout-btn" @click="logout">退出登录</button>
    </div>
  </div>
</template>

<style scoped>
.card {
  width: 380px;
  background: #fff;
  border-radius: 16px;
  padding: 36px 32px;
  box-shadow: 0 12px 40px rgba(31, 56, 88, 0.12);
  text-align: center;
}

.card-header .logo {
  font-size: 44px;
  line-height: 1;
}

.card-header h1 {
  margin-top: 10px;
  font-size: 22px;
  color: #1f3858;
}

.subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #8a97a8;
  line-height: 1.5;
}

.qr-wrap {
  position: relative;
  width: 200px;
  height: 200px;
  margin: 22px auto 0;
  border: 1px solid #e4e9f0;
  border-radius: 12px;
  overflow: hidden;
}

.qr {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
}

.qr-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9aa7b8;
  background: #f6f8fb;
  font-size: 14px;
}

.qr-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 15px;
  animation: fade-in 0.25s ease;
}

.confirmed-mask {
  background: rgba(18, 183, 106, 0.92);
}

.mask-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #fff;
  color: #12b76a;
  font-size: 28px;
  line-height: 48px;
}

.code-box {
  margin-top: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 12px 16px;
  background: #f4f7fb;
  border-radius: 10px;
}

.code-box.disabled {
  opacity: 0.55;
}

.code-label {
  font-size: 13px;
  color: #66788f;
}

.code-value {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 4px;
  color: #1d6ff2;
  font-family: 'Courier New', monospace;
}

.countdown {
  font-size: 13px;
  color: #98a5b5;
  min-width: 38px;
  text-align: left;
}

.steps {
  margin: 18px 0 0;
  padding-left: 20px;
  text-align: left;
  color: #5b6b80;
  font-size: 13px;
  line-height: 1.9;
}

.message {
  margin-top: 14px;
  font-size: 14px;
}

.message.confirmed {
  color: #12b76a;
}

.message.expired,
.message.error {
  color: #e5484d;
}

.refresh-btn {
  margin-top: 16px;
  width: 100%;
  padding: 10px 0;
  font-size: 15px;
  color: #fff;
  background: #1d6ff2;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.2s;
}

.refresh-btn:hover {
  background: #1559c4;
}

@keyframes fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* ---------- 首页（登录成功后） ---------- */
.home {
  width: 380px;
}

.home-card {
  background: #fff;
  border-radius: 16px;
  padding: 40px 32px;
  box-shadow: 0 12px 40px rgba(31, 56, 88, 0.12);
  text-align: center;
  animation: fade-in 0.3s ease;
}

.avatar {
  width: 64px;
  height: 64px;
  margin: 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, #12b76a, #0e9f5c);
  color: #fff;
  font-size: 34px;
  line-height: 64px;
  box-shadow: 0 6px 18px rgba(18, 183, 106, 0.35);
}

.home-card h1 {
  margin-top: 16px;
  font-size: 22px;
  color: #1f3858;
}

.home-sub {
  margin-top: 6px;
  font-size: 13px;
  color: #8a97a8;
}

.info-list {
  margin-top: 24px;
  border-top: 1px solid #eef2f7;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 4px;
  border-bottom: 1px solid #eef2f7;
}

.info-label {
  font-size: 13px;
  color: #8a97a8;
}

.info-value {
  font-size: 13px;
  color: #1f3858;
  font-weight: 500;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout-btn {
  margin-top: 24px;
  width: 100%;
  padding: 10px 0;
  font-size: 15px;
  color: #e5484d;
  background: #fef2f2;
  border: 1px solid #fdd8d8;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
}

.logout-btn:hover {
  background: #fde3e3;
}
</style>

import http from "k6/http";
import { check, sleep } from "k6";

const base = __ENV.BASE_URL || "http://127.0.0.1:8080";
const password = __ENV.LOAD_PASSWORD || "load-password-123";

export const options = {
  scenarios: {
    hot_order: { executor: "constant-arrival-rate", rate: Number(__ENV.RATE || 100), timeUnit: "1s", duration: __ENV.DURATION || "1m", preAllocatedVUs: 100, maxVUs: 1000 }
  }
};

export function setup() {
  const suffix = `${Date.now()}-${__VU}`;
  const headers = { "Content-Type": "application/json" };
  const register = (name) => http.post(`${base}/api/auth/register`, JSON.stringify({ username: `${name}-${suffix}`, nickname: name, password }), { headers });
  const login = (name) => http.post(`${base}/api/auth/login`, JSON.stringify({ username: `${name}-${suffix}`, password }), { headers });
  const publisherRegister = register("k6-publisher");
  const publisherLogin = login("k6-publisher");
  const takerRegister = register("k6-taker");
  const takerLogin = login("k6-taker");
  check(publisherRegister, { "publisher registered": (response) => response.status === 200 });
  check(takerRegister, { "taker registered": (response) => response.status === 200 });
  const publisherToken = publisherLogin.json("data.accessToken");
  const takerToken = takerLogin.json("data.accessToken");
  http.post(`${base}/api/users/me/recharge`, JSON.stringify({ amountCents: 10000000, idemKey: `k6-${suffix}` }), { headers: { ...headers, Authorization: `Bearer ${publisherToken}` } });
  const order = http.post(`${base}/api/orders`, JSON.stringify({ title: "k6 hot order", rewardCents: 1000, claimTtlSeconds: 120 }), { headers: { ...headers, Authorization: `Bearer ${publisherToken}` } });
  return { orderId: order.json("data.id"), takerToken };
}

export default function (data) {
  const response = http.post(`${base}/api/orders/${data.orderId}/grab`, null, { headers: { Authorization: `Bearer ${data.takerToken}` } });
  check(response, { "grab is HTTP response": (value) => value.status === 200 || value.status === 409 || value.status === 429 });
  sleep(0.01);
}

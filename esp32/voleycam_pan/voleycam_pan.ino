// VoleyCam - Cabezal de giro (pan) con ESP32 + servo (HS-422) por Bluetooth LE
//
// Placa: "ESP32 Dev Module" (paquete esp32 de Espressif 2.0.x)
// Librería: ESP32Servo (Kevin Harrington)
// Conexión: señal del servo -> GPIO 18, servo a 5 V (power bank) con GND común a la ESP32.
//
// Se anuncia como "VoleyCam-Pan". La app VoleyCam se conecta sola y le manda órdenes de texto:
//   L / R      girar a la izquierda / derecha. Hay que repetirla cada < 0,7 s (al soltar el botón se para sola)
//   S          parar
//   C          volver al centro (suave)
//   V1..V5     velocidad (1 lenta ... 5 rápida)
//   A1500      ir a una posición en microsegundos (suave)
// Responde por notificación con el estado: "P1500 V3 M0" (posición, velocidad, moviéndose 0/1).
//
// También acepta las mismas órdenes por el Monitor serie (115200) para probar sin la app.

#include <ESP32Servo.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---------- Ajustes ----------
const int PIN_SERVO = 18;
const int US_CENTRO = 1500;   // posición con la cámara mirando al frente
const int US_MIN = 900;       // topes de seguridad (no forzar el servo)
const int US_MAX = 2100;
const char *NOMBRE_BLE = "VoleyCam-Pan";
// Velocidad máxima de cada nivel, en microsegundos por segundo (~10 us = 1 grado)
const float VEL_NIVEL[6] = {0, 40, 80, 150, 250, 400};
const float ACEL = 800;           // us/s^2: arranque y frenada suaves
const uint32_t KEEPALIVE_MS = 700; // si no llega otra L/R en este tiempo, frena

#define SERVICE_UUID "6e5a0001-7a1c-4c5e-9d3b-5f0c0a7e0c01"
#define RX_UUID      "6e5a0002-7a1c-4c5e-9d3b-5f0c0a7e0c01"   // la app escribe aquí
#define TX_UUID      "6e5a0003-7a1c-4c5e-9d3b-5f0c0a7e0c01"   // la ESP32 notifica el estado

// ---------- Estado ----------
Servo servo;
float pos = US_CENTRO;      // posición actual (us)
float vel = 0;              // velocidad actual (us/s, con signo)
int dir = 0;                // -1 izquierda, 0 parado, +1 derecha (orden manual)
int nivel = 3;
int destino = -1;           // posición objetivo (C o A); -1 = ninguna
uint32_t ultimaOrden = 0;
BLECharacteristic *tx = nullptr;
bool conectado = false;
volatile bool hayOrden = false;
String orden = "";
portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;

void procesar(String s) {
  s.trim(); s.toUpperCase();
  if (s.length() == 0) return;
  char c = s.charAt(0);
  if (c == 'L' || c == 'R') { dir = (c == 'L') ? -1 : 1; destino = -1; ultimaOrden = millis(); }
  else if (c == 'S') { dir = 0; destino = -1; }
  else if (c == 'C') { dir = 0; destino = US_CENTRO; }
  else if (c == 'V') { int n = s.substring(1).toInt(); if (n >= 1 && n <= 5) nivel = n; }
  else if (c == 'A') { int a = s.substring(1).toInt(); if (a > 0) { dir = 0; destino = constrain(a, US_MIN, US_MAX); } }
}

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *ch) override {
    String v = String(ch->getValue().c_str());
    portENTER_CRITICAL(&mux); orden = v; hayOrden = true; portEXIT_CRITICAL(&mux);
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override { conectado = true; }
  void onDisconnect(BLEServer *s) override {
    conectado = false; dir = 0;                // si se pierde la conexión, se para
    BLEDevice::startAdvertising();             // y vuelve a anunciarse para reconectar
  }
};

void notificar() {
  if (!tx || !conectado) return;
  char b[32];
  snprintf(b, sizeof(b), "P%d V%d M%d", (int)pos, nivel, (abs(vel) > 1) ? 1 : 0);
  tx->setValue((uint8_t *)b, strlen(b));
  tx->notify();
}

void setup() {
  Serial.begin(115200);
  servo.setPeriodHertz(50);
  servo.attach(PIN_SERVO, 500, 2500);
  servo.writeMicroseconds(US_CENTRO);   // al arrancar siempre mira al frente

  BLEDevice::init(NOMBRE_BLE);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *svc = server->createService(SERVICE_UUID);
  BLECharacteristic *rx = svc->createCharacteristic(RX_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCallbacks());
  tx = svc->createCharacteristic(TX_UUID, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  tx->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("VoleyCam-Pan listo. Ordenes: L R S C V1..V5 A1500");
}

void loop() {
  static uint32_t tPrev = millis(), tNotif = 0;
  static int ultimaNotif = -1;

  if (Serial.available()) procesar(Serial.readStringUntil('\n'));
  if (hayOrden) {
    String s; portENTER_CRITICAL(&mux); s = orden; hayOrden = false; portEXIT_CRITICAL(&mux);
    procesar(s);
  }

  uint32_t t = millis();
  float dt = (t - tPrev) / 1000.0f;
  if (dt < 0.02f) return;          // 50 veces por segundo
  tPrev = t;

  // Seguridad: sin órdenes L/R recientes, frena
  if (dir != 0 && t - ultimaOrden > KEEPALIVE_MS) dir = 0;

  float vmax = VEL_NIVEL[nivel];
  float objetivo = 0;              // velocidad deseada
  if (dir != 0) objetivo = dir * vmax;
  else if (destino >= 0) {
    float falta = destino - pos;
    // velocidad que permite frenar a tiempo: v = sqrt(2·a·d)
    float vfreno = sqrtf(2 * ACEL * fabsf(falta));
    objetivo = (falta > 0 ? 1 : -1) * min(vmax, vfreno);
    if (fabsf(falta) < 1.5f) { pos = destino; vel = 0; objetivo = 0; destino = -1; }
  }
  // Rampa de aceleración
  float dv = ACEL * dt;
  if (vel < objetivo) vel = min(vel + dv, objetivo);
  else if (vel > objetivo) vel = max(vel - dv, objetivo);

  pos += vel * dt;
  if (pos <= US_MIN) { pos = US_MIN; vel = 0; }
  if (pos >= US_MAX) { pos = US_MAX; vel = 0; }
  servo.writeMicroseconds((int)roundf(pos));

  // Estado a la app: al cambiar y cada 2 s
  int p = (int)pos;
  if ((p != ultimaNotif && t - tNotif > 150) || t - tNotif > 2000) { notificar(); ultimaNotif = p; tNotif = t; }
}

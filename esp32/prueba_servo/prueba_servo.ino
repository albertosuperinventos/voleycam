// VoleyCam - Prueba del servo (HS-422) con ESP32
// 1) Al arrancar centra el servo (1500 us) y espera 5 s -> ajusta el plato centrado.
// 2) Luego gira despacio a un lado, al otro y vuelve al centro, en bucle.
// Monitor serie a 115200: escribe un numero entre 600 y 2400 (us) para moverlo a mano,
// o "c" para centrar, "p" para pausar/reanudar el barrido.

#include <ESP32Servo.h>

const int PIN_SERVO = 18;
const int US_MIN = 900;     // topes prudentes (el HS-422 admite ~600-2400)
const int US_MAX = 2100;
const int US_CENTRO = 1500;

Servo servo;
int actual = US_CENTRO;
bool barrido = true;

void irSuave(int destino, int pasoUs = 2, int msPaso = 10) {
  destino = constrain(destino, 600, 2400);
  while (actual != destino) {
    actual += (destino > actual) ? min(pasoUs, destino - actual) : -min(pasoUs, actual - destino);
    servo.writeMicroseconds(actual);
    delay(msPaso);
    if (Serial.available()) return;   // deja que una orden interrumpa
  }
}

void setup() {
  Serial.begin(115200);
  servo.setPeriodHertz(50);
  servo.attach(PIN_SERVO, 500, 2500);
  servo.writeMicroseconds(US_CENTRO);
  Serial.println("Servo en el CENTRO (1500 us). Tienes 5 s para ajustar el plato...");
  delay(5000);
}

void loop() {
  if (Serial.available()) {
    String s = Serial.readStringUntil('\n'); s.trim();
    if (s == "c") { barrido = false; irSuave(US_CENTRO); Serial.println("Centro"); }
    else if (s == "p") { barrido = !barrido; Serial.println(barrido ? "Barrido ON" : "Barrido OFF"); }
    else if (s.toInt() > 0) { barrido = false; irSuave(s.toInt()); Serial.printf("Posicion %d us\n", actual); }
  }
  if (barrido) {
    irSuave(US_MIN); delay(500);
    irSuave(US_MAX); delay(500);
    irSuave(US_CENTRO); delay(1500);
  }
}

import socket

HOST = '0.0.0.0'  # слушать на всех интерфейсах
PORT = 12345      # любой свободный порт

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.bind((HOST, PORT))
    s.listen()
    print(f"Сервер запущен на порту {PORT}")
    conn, addr = s.accept()
    with conn:
        print('Подключено:', addr)
        while True:
            data = conn.recv(1024)
            if not data:
                break
            print("Получено:", data.decode())
            conn.sendall(data)  # отправляем обратно (эхо-сервер)

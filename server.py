import socket
import threading

HOST = '0.0.0.0'
PORT = 12345

clients = []       # список клиентских сокетов
messages_history = []  # список всех сообщений

def handle_client(conn, addr):
    print('Подключено:', addr)
    try:
        # Отправляем историю сообщений новому клиенту
        for msg in messages_history:
            conn.sendall((msg + "\n").encode())

        while True:
            data = conn.recv(1024)
            if not data:
                print(f"Клиент {addr} отключился.")
                break
            message = data.decode().strip()
            full_message = f"{message}"
            print(full_message)
            
            # Сохраняем сообщение
            messages_history.append(full_message)
            
            # Отправляем всем, кроме отправителя
            broadcast(full_message, conn)
    except ConnectionResetError:
        print(f"Клиент {addr} неожиданно отключился.")
    finally:
        conn.close()
        clients.remove(conn)

def broadcast(message, sender_conn):
    for client in clients:
        if client != sender_conn:
            try:
                client.sendall((message + "\n").encode())
            except Exception:
                clients.remove(client)

def send_messages():
    while True:
        msg = input()
        if msg.lower() == 'exit':
            print("Завершение работы сервера.")
            break
        full_message = f"Сервер: {msg}"
        messages_history.append(full_message)
        for conn in clients[:]:
            try:
                conn.sendall((full_message + "\n").encode())
            except:
                clients.remove(conn)

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.bind((HOST, PORT))
    s.listen()
    print(f"Сервер запущен на порту {PORT}. Ожидание подключений...")

    threading.Thread(target=send_messages, daemon=True).start()

    try:
        while True:
            conn, addr = s.accept()
            clients.append(conn)
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\nОстановка сервера по Ctrl+C")
    finally:
        for c in clients:
            c.close()

import socket
import threading

HOST = '0.0.0.0'
PORT = 12345

clients = []  # список клиентских сокетов
messages_history = []  # история сообщений в формате {"type": "TEXT"|"IMAGE", "data": ...}

def recv_header(conn):
    header = {}
    buffer = ""
    prev_was_newline = False
    while True:
        ch = conn.recv(1)
        if not ch:
            return None
        c = ch.decode('utf-8', errors='ignore')
        buffer += c
        if c == '\n':
            if prev_was_newline:
                break
            prev_was_newline = True
        elif c.strip():
            prev_was_newline = False
    for line in buffer.strip().split('\n'):
        if ':' in line:
            key, value = line.split(':', 1)
            header[key.strip().upper()] = value.strip()
    return header

def recv_exact(conn, length):
    data = b''
    while len(data) < length:
        more = conn.recv(length - len(data))
        if not more:
            return None
        data += more
    return data

def send_history(conn):
    for msg in messages_history:
        if msg["type"] == "TEXT":
            data = msg["data"].encode('utf-8')
            header = f"TYPE:TEXT\nLENGTH:{len(data)}\n\n"
            try:
                conn.sendall(header.encode('utf-8'))
                conn.sendall(data)
            except Exception as e:
                print(f"Ошибка отправки истории клиенту (текст): {e}")
        elif msg["type"] == "IMAGE":
            data = msg["data"]
            header = f"TYPE:IMAGE\nLENGTH:{len(data)}\n\n"
            try:
                conn.sendall(header.encode('utf-8'))
                conn.sendall(data)
            except Exception as e:
                print(f"Ошибка отправки истории клиенту (изображение): {e}")

def broadcast(message, sender_conn):
    if message["type"] == "TEXT":
        data = message["data"].encode('utf-8')
        header = f"TYPE:TEXT\nLENGTH:{len(data)}\n\n"
    elif message["type"] == "IMAGE":
        data = message["data"]
        header = f"TYPE:IMAGE\nLENGTH:{len(data)}\n\n"
    else:
        return

    for client in clients[:]:
        if client != sender_conn:
            try:
                client.sendall(header.encode('utf-8'))
                client.sendall(data)
            except Exception:
                clients.remove(client)

def handle_client(conn, addr):
    print('Подключено:', addr)
    try:
        send_history(conn)

        while True:
            header = recv_header(conn)
            if header is None:
                print(f"Клиент {addr} отключился (неполный заголовок).")
                break

            length = int(header.get("LENGTH", 0))
            msg_type = header.get("TYPE", "TEXT").upper()

            body_bytes = recv_exact(conn, length)
            if body_bytes is None:
                print(f"Клиент {addr} отключился (неполное тело).")
                break

            if msg_type == "TEXT":
                message = body_bytes.decode('utf-8', errors='ignore')
                print(f"Получено сообщение от {addr}: {message}")

                messages_history.append({"type": "TEXT", "data": message})
                broadcast({"type": "TEXT", "data": message}, conn)

            elif msg_type == "IMAGE":
                print(f"Получено изображение от {addr}, размер {length} байт")
                messages_history.append({"type": "IMAGE", "data": body_bytes})
                broadcast({"type": "IMAGE", "data": body_bytes}, conn)

            else:
                print(f"Получен неподдерживаемый тип сообщения {msg_type} от {addr}")

    except ConnectionResetError:
        print(f"Клиент {addr} неожиданно отключился.")
    finally:
        conn.close()
        if conn in clients:
            clients.remove(conn)
        print(f"Клиент {addr} отключился.")

def send_messages():
    while True:
        msg = input()
        if msg.lower() == 'exit':
            print("Завершение работы сервера.")
            break
        full_message = f"Сервер: {msg}"
        messages_history.append({"type": "TEXT", "data": full_message})
        broadcast({"type": "TEXT", "data": full_message}, None)

def main():
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

if __name__ == "__main__":
    main()

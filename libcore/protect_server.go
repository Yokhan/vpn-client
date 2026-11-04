package libcore

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"

	"golang.org/x/sys/unix"
)

// serveProtect replaces the Matsuri protect_server package. It listens on a
// unix socket, receives one file descriptor per connection, and lets the
// Android side protect it (or applies the provided callback).
func serveProtect(path string, protectCtl func(fd int)) io.Closer {
	_ = os.Remove(path)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		log.Println("protect server listen:", err)
		return nil
	}
	_ = os.Chmod(path, 0o777)

	go func() {
		for {
			conn, acceptErr := listener.AcceptUnix()
			if acceptErr != nil {
				return
			}
			go func(conn *net.UnixConn) {
				defer conn.Close()
				fd, recvErr := receiveFd(conn)
				if recvErr != nil {
					log.Println("protect server receive:", recvErr)
					return
				}
				defer unix.Close(fd)
				if protectCtl != nil {
					protectCtl(fd)
				}
				_, _ = conn.Write([]byte{1})
			}(conn)
		}
	}()

	return listener
}

func receiveFd(conn *net.UnixConn) (int, error) {
	rawConn, err := conn.SyscallConn()
	if err != nil {
		return 0, err
	}
	var (
		fd       int
		recvErr  error
		oob      = make([]byte, unix.CmsgSpace(4))
		foundFd  bool
		parseErr error
	)
	controlErr := rawConn.Control(func(socket uintptr) {
		_, _, _, _, recvErr = unix.Recvmsg(int(socket), nil, oob, 0)
		if recvErr != nil {
			return
		}
		msgs, err := unix.ParseSocketControlMessage(oob)
		if err != nil {
			parseErr = err
			return
		}
		if len(msgs) != 1 {
			parseErr = fmt.Errorf("invalid control message count: %d", len(msgs))
			return
		}
		fds, err := unix.ParseUnixRights(&msgs[0])
		if err != nil {
			parseErr = err
			return
		}
		if len(fds) != 1 {
			parseErr = fmt.Errorf("invalid fd count: %d", len(fds))
			return
		}
		fd = fds[0]
		foundFd = true
	})
	if controlErr != nil {
		return 0, controlErr
	}
	if recvErr != nil {
		return 0, recvErr
	}
	if parseErr != nil {
		return 0, parseErr
	}
	if !foundFd {
		return 0, fmt.Errorf("no file descriptor received")
	}
	return fd, nil
}

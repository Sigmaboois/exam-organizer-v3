// Package wabridge lets the Android app send lesson links to WhatsApp groups.
//
// The phone links to the teacher's WhatsApp as its own device, like WhatsApp Web, using
// whatsmeow (an unofficial WhatsApp client): WhatsApp has no official way to post in normal
// groups. Messages go straight to WhatsApp, so it works with the screen locked and nothing is
// tapped for the teacher.
//
// Built into an Android library with gomobile (see ../scripts/build-wabridge.sh). Kotlin calls
// the exported functions below; they are safe to call from any thread.
package wabridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// Listener hears about the link: state is one of starting, code, connected, disconnected,
// expired, logged_out, error. detail is the QR text for "code", or what went wrong.
type Listener interface {
	OnState(state string, detail string)
}

var (
	mu       sync.Mutex
	client   *whatsmeow.Client
	listener Listener
	qrReady  chan struct{}
	dbPath   string
	stale    bool // logged out from the phone: the next Open starts afresh
	// One Connect at a time: the Lessons screen and the background sender can both call it
	connecting sync.Mutex
)

func tell(state, detail string) {
	if l := listener; l != nil {
		l.OnState(state, detail)
	}
}

// Open loads the saved login (if any) from the database file. Call it once before anything else.
func Open(path string, l Listener) error {
	mu.Lock()
	defer mu.Unlock()
	listener = l
	if client != nil && path == dbPath && !stale {
		return nil
	}
	stale = false
	if client != nil {
		client.Disconnect()
		client = nil
	}
	ctx := context.Background()
	container, err := sqlstore.New(ctx, "sqlite3", "file:"+path+"?_foreign_keys=on&_busy_timeout=5000", waLog.Noop)
	if err != nil {
		return fmt.Errorf("couldn't open the WhatsApp login: %w", err)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		return fmt.Errorf("couldn't read the WhatsApp login: %w", err)
	}
	client = whatsmeow.NewClient(device, waLog.Noop)
	client.AddEventHandler(onEvent)
	dbPath = path
	return nil
}

func onEvent(evt interface{}) {
	switch e := evt.(type) {
	case *events.Connected:
		tell("connected", Account())
	case *events.PairSuccess:
		tell("starting", "")
	case *events.Disconnected:
		tell("disconnected", "")
	case *events.LoggedOut:
		mu.Lock()
		stale = true
		mu.Unlock()
		tell("logged_out", "This phone was unlinked from WhatsApp. Link it again to keep sending.")
	case *events.StreamReplaced:
		tell("error", "WhatsApp was opened by another copy of Exam Organizer.")
	case *events.TemporaryBan:
		tell("error", "WhatsApp has blocked this link for a while: "+e.String())
	case *events.ConnectFailure:
		tell("error", fmt.Sprintf("WhatsApp refused the connection (%v).", e.Reason))
	case *events.ClientOutdated:
		tell("error", "This version of Exam Organizer is too old for WhatsApp. Install the latest version.")
	}
}

func current() (*whatsmeow.Client, error) {
	mu.Lock()
	defer mu.Unlock()
	if client == nil {
		return nil, errors.New("WhatsApp isn't set up yet")
	}
	return client, nil
}

// IsLinked is true once this phone has been linked (it then reconnects without a code).
func IsLinked() bool {
	c, err := current()
	return err == nil && c.Store.ID != nil
}

// IsConnected is true while linked and online.
func IsConnected() bool {
	c, err := current()
	return err == nil && c.IsConnected() && c.IsLoggedIn()
}

// Account is the linked phone number, like "+201001234567".
func Account() string {
	c, err := current()
	if err != nil || c.Store.ID == nil {
		return ""
	}
	return "+" + c.Store.ID.User
}

// Connect goes online. If the phone isn't linked yet it starts linking: QR codes come to the
// listener as "code", and LinkWithPhoneNumber can be used instead.
func Connect() error {
	connecting.Lock()
	defer connecting.Unlock()
	c, err := current()
	if err != nil {
		return err
	}
	if c.IsConnected() {
		return nil
	}
	tell("starting", "")
	if c.Store.ID != nil {
		err := c.Connect()
		if errors.Is(err, whatsmeow.ErrAlreadyConnected) {
			return nil // online already (whatsmeow reconnected by itself): that's what was asked
		}
		return err
	}
	ready := make(chan struct{})
	mu.Lock()
	qrReady = ready
	mu.Unlock()
	codes, err := c.GetQRChannel(context.Background())
	if err != nil {
		return err
	}
	if err := c.Connect(); err != nil {
		return err
	}
	go func() {
		first := true
		for item := range codes {
			switch item.Event {
			case "code":
				if first {
					close(ready)
					first = false
				}
				tell("code", item.Code)
			case "timeout":
				tell("expired", "")
			case "success":
				// events.Connected follows
			default:
				if item.Error != nil {
					tell("error", item.Error.Error())
				}
			}
		}
	}()
	return nil
}

// LinkWithPhoneNumber returns the 8-letter code to type in WhatsApp (Linked devices > Link a
// device > Link with phone number instead). phone is the full number with the country code.
func LinkWithPhoneNumber(phone string) (string, error) {
	c, err := current()
	if err != nil {
		return "", err
	}
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, phone)
	if len(digits) < 8 {
		return "", errors.New("type the whole number, starting with the country code")
	}
	if err := Connect(); err != nil {
		return "", err
	}
	mu.Lock()
	ready := qrReady
	mu.Unlock()
	if ready != nil {
		select {
		case <-ready:
		case <-time.After(30 * time.Second):
			return "", errors.New("WhatsApp didn't answer. Check the internet connection")
		}
	}
	return c.PairPhone(context.Background(), digits, true, whatsmeow.PairClientChrome, "Chrome (Exam Organizer)")
}

// WaitConnected waits up to the given number of seconds to be online. Used before sending.
func WaitConnected(seconds int) bool {
	deadline := time.Now().Add(time.Duration(seconds) * time.Second)
	for time.Now().Before(deadline) {
		if IsConnected() {
			return true
		}
		time.Sleep(250 * time.Millisecond)
	}
	return IsConnected()
}

type group struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// Groups lists every group this WhatsApp account is in, as JSON: [{"id": "...@g.us", "name": "..."}].
func Groups() (string, error) {
	c, err := current()
	if err != nil {
		return "", err
	}
	if !c.IsLoggedIn() {
		return "", errors.New("link WhatsApp first")
	}
	found, err := c.GetJoinedGroups(context.Background())
	if err != nil {
		return "", err
	}
	list := make([]group, 0, len(found))
	for _, g := range found {
		name := strings.TrimSpace(g.Name)
		if name == "" {
			name = "Unnamed group"
		}
		list = append(list, group{ID: g.JID.String(), Name: name})
	}
	sort.Slice(list, func(i, j int) bool { return strings.ToLower(list[i].Name) < strings.ToLower(list[j].Name) })
	out, err := json.Marshal(list)
	return string(out), err
}

// Send posts text to a group (its id from Groups), giving up after the given number of seconds.
func Send(groupID string, text string, seconds int) error {
	c, err := current()
	if err != nil {
		return err
	}
	if !c.IsLoggedIn() {
		return errors.New("WhatsApp isn't linked on this phone")
	}
	jid, err := types.ParseJID(groupID)
	if err != nil {
		return fmt.Errorf("that group can't be found: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(seconds)*time.Second)
	defer cancel()
	_, err = c.SendMessage(ctx, jid, &waE2E.Message{Conversation: proto.String(text)})
	return err
}

// Disconnect goes offline (the phone stays linked).
func Disconnect() {
	if c, err := current(); err == nil {
		c.Disconnect()
	}
}

// Unlink logs this phone out of WhatsApp; it disappears from Linked devices. The saved login is
// deleted even when offline (Logout needs a connection), so it isn't loaded and used again.
func Unlink() error {
	c, err := current()
	if err != nil {
		return err
	}
	if c.Store.ID == nil {
		c.Disconnect()
		return nil
	}
	if c.IsConnected() {
		_ = c.Logout(context.Background()) // also deletes the login when WhatsApp answers
	}
	c.Disconnect()
	if err := c.Store.Delete(context.Background()); err != nil { // no-op if Logout deleted it
		return fmt.Errorf("couldn't delete the WhatsApp login: %w", err)
	}
	mu.Lock()
	client = nil
	dbPath = ""
	mu.Unlock()
	return nil
}

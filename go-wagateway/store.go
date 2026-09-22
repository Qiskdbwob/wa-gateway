package wagateway

import (
	"context"
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// initStore sets up the SQLite database and returns the whatsmeow device store and container.
func initStore(dbPath string, log waLog.Logger) (*sql.DB, *sqlstore.Container, *store.Device, error) {
	// Enable foreign keys pragma for SQLite as required by whatsmeow
	db, err := sql.Open("sqlite", fmt.Sprintf("file:%s?_pragma=foreign_keys(1)", dbPath))
	if err != nil {
		return nil, nil, nil, fmt.Errorf("failed to open sqlite db: %w", err)
	}

	container := sqlstore.NewWithDB(db, "sqlite", log)
	err = container.Upgrade(context.Background())
	if err != nil {
		_ = db.Close()
		return nil, nil, nil, fmt.Errorf("failed to upgrade sqlite db schema: %w", err)
	}

	device, err := container.GetFirstDevice(context.Background())
	if err != nil {
		_ = container.Close()
		_ = db.Close()
		return nil, nil, nil, fmt.Errorf("failed to get first device: %w", err)
	}

	if device == nil {
		device = container.NewDevice()
		err = container.PutDevice(context.Background(), device)
		if err != nil {
			_ = container.Close()
			_ = db.Close()
			return nil, nil, nil, fmt.Errorf("failed to put new device: %w", err)
		}
	}

	return db, container, device, nil
}

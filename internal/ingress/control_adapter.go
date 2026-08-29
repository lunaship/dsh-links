package ingress

import (
	"github.com/dsh-links/dsh-links-relay/internal/control"
	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
)

// InProcessControl adapts control.Control to ControlAPI
type InProcessControl struct {
	ctrl *control.Control
}

func NewInProcessControl(ctrl *control.Control) *InProcessControl {
	return &InProcessControl{ctrl: ctrl}
}

func (c *InProcessControl) Enroll(req *EnrollProxyRequest) (*EnrollProxyResponse, error) {
	res, err := c.ctrl.Enroll(&control.EnrollRequest{
		InviteCode:    req.InviteCode,
		HostId:        req.HostId,
		HostPublicKey: req.HostPublicKey,
		Ts:            req.Ts,
		Nonce:         req.Nonce,
		Challenge:     req.Challenge,
		Proof:         req.Proof,
	})
	if err != nil {
		return nil, err
	}
	return &EnrollProxyResponse{
		RouteId:     res.RouteId,
		RouteSecret: res.RouteSecret,
		Capability:  res.Capability,
		Generation:  res.Generation,
	}, nil
}

func (c *InProcessControl) LookupHostByRoute(routeId []byte) (string, uint64, []byte, int, bool, error) {
	h, err := c.ctrl.GetHostByRoute(routeId)
	if err != nil {
		return "", 0, nil, 0, false, err
	}
	revoked := h.RevokedAt != nil
	return h.ID, uint64(h.Generation), h.HostPubKey, h.MaxStreams, revoked, nil
}

func (c *InProcessControl) VerifyRouteMAC(req *RouteMACProxyRequest) error {
	return c.ctrl.VerifyRouteMAC(control.RouteMACRequest{
		Operation: req.Operation, RouteID: req.RouteID, StreamID: req.StreamID,
		Generation: req.Generation, Ts: req.Ts, Nonce: req.Nonce,
		Challenge: req.Challenge, MAC: req.MAC,
	})
}

func (c *InProcessControl) VerifyCapability(cap string) (*cryptoutil.CapabilityPayload, error) {
	return cryptoutil.VerifyCapability(c.ctrl.IssuerPublicKey(), cap)
}

func (c *InProcessControl) Renew(req *RenewProxyRequest) (string, error) {
	return c.ctrl.Renew(&control.RenewRequest{
		RouteId:       req.RouteId,
		HostId:        req.HostId,
		HostPubKey:    req.HostPubKey,
		Ts:            req.Ts,
		Nonce:         req.Nonce,
		Challenge:     req.Challenge,
		Proof:         req.Proof,
		OldCapability: req.OldCapability,
	})
}

// Ensure InProcessControl satisfies ControlAPI
var _ ControlAPI = (*InProcessControl)(nil)

func (c *InProcessControl) ReportUsage(routeID []byte, rx, tx int64, connects int) error {
	return c.ctrl.ReportUsage(routeID, rx, tx, connects)
}

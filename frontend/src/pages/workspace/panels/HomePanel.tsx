import React from 'react';
import { Stat } from '../ui';
import type { WorkspaceData } from '../../../types/workspace';
import { PanelKey } from '../config';

interface Props {
  workspace: WorkspaceData;
  setPanel: (key: PanelKey) => void;
}

export function HomePanel({ workspace, setPanel }: Props) {
  const d = workspace.dashboard;
  return (
    <>
      <div className="ck-alert ck-alert-am"><span>⚠</span><div><strong>{d.feeOverdueCount} students</strong> have overdue fees — Term 2 deadline is Jan 31. <button className="ck-inline-link" onClick={() => setPanel('fees')}>Review →</button></div></div>
      <div className="ck-stats ck-s4">
        <Stat label="Students" value={d.students} sub={`${d.sections} sections`} pill="+3 this month" tone="blue" onClick={() => setPanel('students')} />
        <Stat label="Today's attendance" value={`${d.attendancePercent}%`} sub={`${d.attendancePresent} / ${d.students} present`} pill="Marked ✓" tone="green" onClick={() => setPanel('attendance')} />
        <Stat label="Fees collected" value={`₹${d.feeCollectedLakh}L`} sub={`of ₹${d.feeTargetLakh}L this term`} pill={`${d.feeOverdueCount} overdue`} tone="red" onClick={() => setPanel('fees')} />
        <Stat label="Firefighting" value={d.firefightingActive} sub="Active requests" pill={`${d.pendingApprovals} need approval`} tone="orange" onClick={() => setPanel('ff-dashboard')} />
      </div>
      <div className="ck-two-col">
        <div className="ck-card">
          <div className="ck-card-h"><div className="ck-card-t">Recent activity</div><div className="ck-card-a">See all</div></div>
          {workspace.recentActivity.map((item, index) => (
            <div className="ck-act-row" key={index}>
              <div className="ck-act-icon">{item.icon}</div>
              <div className="ck-act-info"><div className="ck-act-name">{item.title}</div><div className="ck-act-meta">{item.meta}</div></div>
              <span className={`ck-status ${item.tagClass || 'sg'}`}>{item.tag}</span>
            </div>
          ))}
        </div>
        <div className="ck-card">
          <div className="ck-card-h"><div className="ck-card-t">Action center</div></div>
          <div className="ck-list-block">
            <button className="ck-cta-row" onClick={() => setPanel('addstudent')}><strong>Enroll a student</strong><span>Create admission and auto-generate fee dues</span></button>
            <button className="ck-cta-row" onClick={() => setPanel('catalog')}><strong>Place a supply order</strong><span>Use the catalog for uniforms, notebooks and more</span></button>
            <button className="ck-cta-row" onClick={() => setPanel('ff-approvals')}><strong>Review firefighting approvals</strong><span>{d.pendingApprovals} requests awaiting decision</span></button>
            <button className="ck-cta-row" onClick={() => setPanel('planning')}><strong>Update the annual plan</strong><span>Lock in pricing and quantities for the next term</span></button>
          </div>
        </div>
      </div>
    </>
  );
}

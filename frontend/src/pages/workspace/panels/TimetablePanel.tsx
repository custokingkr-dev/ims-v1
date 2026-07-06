import { TimetableGrid } from './TimetableGrid';

interface Props {
  readOnly?: boolean;
  staff?: Array<{ id: number | string; name: string }>;
}

export function TimetablePanel({ readOnly, staff }: Props) {
  return <TimetableGrid readOnly={readOnly} staff={staff} />;
}

import type { BlockSpec } from '../types';
import { CdrForApprovalBlock, HeadApprovalBlock } from './ApprovalBlocks';
import { ChecklistBlock } from './ChecklistBlock';
import { DesignFilesBlock } from './DesignFilesBlock';
import { FieldsBlock } from './FieldsBlock';
import { InternalApprovalGroupBlock, IntroMessageBlock, SchoolGroupBlock } from './GroupBlocks';
import { ItemQuantitiesBlock, OrderFolderBlock, SavedTitleCardsBlock, TitleCardQuantitiesBlock } from './QuantityBlocks';
import { LegacyDetailsBlock, SendBackBlock, SignOffBlock } from './SignOffBlocks';
import { VendorDirectoryBlock, VendorGroupsBlock, VendorSelectionBlock } from './VendorBlocks';

/**
 * One stage card, chosen by the template's block type (section 3.3: a closed set). Every card reads its fields, checklists
 * and labels from the template; nothing about a particular step is written here.
 */
export function Block({ block }: { block: BlockSpec }) {
  switch (block.type) {
    case 'ORDER_PDF_LINK': return <FieldsBlock block={{ ...block, fields: block.fields?.length ? block.fields : ['orderPdfLink'] }} defaultSave="Save PDF link" />;
    case 'FIELDS': return <FieldsBlock block={block} />;
    case 'INTRO_MESSAGE': return <IntroMessageBlock block={block} />;
    case 'SCHOOL_GROUP': return <SchoolGroupBlock block={block} />;
    case 'DESIGN_FILES': return <DesignFilesBlock block={block} />;
    case 'CHECKLIST': return <ChecklistBlock block={block} />;
    case 'CDR_FOR_APPROVAL': return <CdrForApprovalBlock block={block} />;
    case 'HEAD_APPROVAL': return <HeadApprovalBlock block={block} />;
    case 'ORDER_FOLDER': return <OrderFolderBlock block={block} />;
    case 'TITLE_CARD_QUANTITIES': return <TitleCardQuantitiesBlock block={block} />;
    case 'SAVED_TITLE_CARDS': return <SavedTitleCardsBlock block={block} />;
    case 'ITEM_QUANTITIES': return <ItemQuantitiesBlock block={block} />;
    case 'VENDOR_DIRECTORY': return <VendorDirectoryBlock block={block} />;
    case 'VENDOR_SELECTION': return <VendorSelectionBlock block={block} />;
    case 'VENDOR_GROUPS': return <VendorGroupsBlock block={block} />;
    case 'INTERNAL_APPROVAL_GROUP': return <InternalApprovalGroupBlock block={block} />;
    case 'SIGN_OFF': return <SignOffBlock block={block} />;
    case 'SEND_BACK': return <SendBackBlock block={block} />;
    case 'LEGACY_DETAILS': return <LegacyDetailsBlock block={block} />;
    default: return null;
  }
}

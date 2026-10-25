import foodFight from './packs/food_fight.json';
import general from './packs/general.json';
import officeChaos from './packs/office_chaos.json';
import saGameNight from './packs/sa_game_night.json';
import wildCard from './packs/wild_card.json';
import type { PackFile, WordEntry } from './validation';

export type { Difficulty, PackFile, WordEntry } from './validation';

export type PackId = 'general' | 'food_fight' | 'office_chaos' | 'sa_game_night' | 'wild_card';

export interface Pack extends PackFile {
  readonly id: PackId;
}

/** Display order in the pack browser. */
export const PACKS: readonly Pack[] = [
  general,
  foodFight,
  officeChaos,
  saGameNight,
  wildCard,
] as Pack[];

export const FREE_PACK_ID: PackId = 'general';

const byId = new Map<string, Pack>(PACKS.map((p) => [p.id, p]));

export function getPack(id: string): Pack | undefined {
  return byId.get(id);
}

export function isPackId(id: string): id is PackId {
  return byId.has(id);
}

export function findEntry(packId: string, entryId: string): WordEntry | undefined {
  return byId.get(packId)?.entries.find((e) => e.id === entryId);
}
